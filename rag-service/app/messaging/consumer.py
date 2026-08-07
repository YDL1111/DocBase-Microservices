"""
RabbitMQ consumer for RAG service.
"""
import json
from typing import Callable, Awaitable

import aio_pika
from aio_pika import IncomingMessage

from app.core.config import settings
from app.core.logging import get_logger
from app.messaging.contracts import KnowledgeEvent
from app.messaging.topology import (
    KNOWLEDGE_EVENTS_EXCHANGE,
    RAG_CONSUMER_QUEUE,
    RAG_INGEST_ROUTING_KEY,
    RAG_DELETE_ROUTING_KEY,
    RETRY_DLX,
    RETRY_30S_QUEUE,
    RETRY_5M_QUEUE,
    RETRY_30M_QUEUE,
    DLQ_QUEUE,
    RETRY_ROUTING_KEYS,
    FAILED_ROUTING_KEY,
    RAG_RETRY_ROUTING_KEY,
)

logger = get_logger(__name__)

# Header key for explicit retry counting (independent of x-death which is lost on republish)
RETRY_COUNT_HEADER = "x-retry-count"


class RagConsumer:
    """Consumes knowledge events from RabbitMQ."""

    def __init__(self, message_handler: Callable[[KnowledgeEvent], Awaitable[None]]):
        self.message_handler = message_handler
        self._connection = None
        self._channel = None

    async def start(self):
        """Start consuming messages."""
        # Use individual connection fields to avoid URL-encoding issues with vhost
        self._connection = await aio_pika.connect_robust(
            host=settings.RABBITMQ_HOST,
            port=settings.RABBITMQ_PORT,
            login=settings.RABBITMQ_USER,
            password=settings.RABBITMQ_PASSWORD,
            virtualhost=settings.RABBITMQ_VHOST,
        )
        # CRITICAL: on_return_raises=True ensures unroutable messages (mandatory=True)
        # raise an exception instead of being silently dropped.
        self._channel = await self._connection.channel(on_return_raises=True)

        # Set prefetch count
        await self._channel.set_qos(prefetch_count=settings.RABBITMQ_CONSUMER_PREFETCH)

        # Declare topology
        await self._declare_topology()

        # Start consuming
        queue = await self._channel.get_queue(RAG_CONSUMER_QUEUE)
        await queue.consume(self._on_message)

        logger.info(f"RAG consumer started on queue: {RAG_CONSUMER_QUEUE}")

    async def stop(self):
        """Stop consuming and close connection."""
        if self._connection:
            await self._connection.close()

    async def _declare_topology(self):
        """Declare exchanges, queues, and bindings."""
        # Main exchange
        exchange = await self._channel.declare_exchange(
            KNOWLEDGE_EVENTS_EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True
        )

        # Retry DLX (direct exchange for retry routing)
        dlx = await self._channel.declare_exchange(
            RETRY_DLX, aio_pika.ExchangeType.DIRECT, durable=True
        )

        # Main queue with DLX - on failure, dead-letter to retry DLX with failed routing key
        queue = await self._channel.declare_queue(
            RAG_CONSUMER_QUEUE,
            durable=True,
            arguments={
                "x-dead-letter-exchange": RETRY_DLX,
                "x-dead-letter-routing-key": FAILED_ROUTING_KEY,
            },
        )
        await queue.bind(exchange, routing_key=RAG_INGEST_ROUTING_KEY)
        await queue.bind(exchange, routing_key=RAG_DELETE_ROUTING_KEY)
        # CRITICAL: Also bind to RAG-only retry routing key (NOT shared document.retry)
        await queue.bind(exchange, routing_key=RAG_RETRY_ROUTING_KEY)

        # Retry queues - each binds to a unique routing key on the DLX
        # After TTL expires, they dead-letter back to main exchange with RAG_RETRY_ROUTING_KEY
        for i, (ttl_ms, queue_name) in enumerate([
            (30000, RETRY_30S_QUEUE),
            (300000, RETRY_5M_QUEUE),
            (1800000, RETRY_30M_QUEUE),
        ]):
            retry_queue = await self._channel.declare_queue(
                queue_name,
                durable=True,
                arguments={
                    "x-message-ttl": ttl_ms,
                    "x-dead-letter-exchange": KNOWLEDGE_EVENTS_EXCHANGE,
                    # CRITICAL: Use RAG-only routing key, NOT document.retry (shared with Ingest)
                    "x-dead-letter-routing-key": RAG_RETRY_ROUTING_KEY,
                },
            )
            await retry_queue.bind(dlx, routing_key=RETRY_ROUTING_KEYS[i])

        # DLQ - binds to retry DLX with failed routing key
        dlq = await self._channel.declare_queue(DLQ_QUEUE, durable=True)
        await dlq.bind(dlx, routing_key=FAILED_ROUTING_KEY)

    async def _on_message(self, message: IncomingMessage):
        """Process incoming message with manual ACK and retry logic."""
        event = None
        try:
            body = json.loads(message.body.decode())
            event = KnowledgeEvent(**body)

            logger.info(f"Received event: {event.event_id} type={event.event_type}")

            # Process the event
            await self.message_handler(event)

            # ACK on success
            await message.ack()

        except Exception as e:
            logger.error(f"Failed to process message: {e}")

            # Determine retry count from explicit header (x-retry-count)
            # This is reliable because we control it on republish (x-death is lost)
            retry_count = self._get_retry_count(message)

            if retry_count < len(RETRY_ROUTING_KEYS):
                # Publish to retry queue with TTL
                retry_key = RETRY_ROUTING_KEYS[retry_count]
                logger.info(f"Publishing to retry queue: {retry_key} (attempt {retry_count + 1})")
                published = await self._publish_to_retry(message.body, event, retry_key, retry_count + 1)
                if published:
                    # Only ACK if retry publish succeeded (confirmed by broker + no return)
                    await message.ack()
                else:
                    # Publish failed - explicitly NACK with requeue for immediate redelivery
                    logger.error(f"Failed to publish retry message, nack+requeue")
                    await self._nack_with_requeue(message)
            else:
                # Max retries exceeded - send to DLQ
                logger.error(f"Max retries exceeded for event, sending to DLQ")
                published = await self._publish_to_dlq(message.body, event)
                if published:
                    await message.ack()
                else:
                    logger.error(f"Failed to publish to DLQ, nack+requeue")
                    await self._nack_with_requeue(message)

    def _get_retry_count(self, message: IncomingMessage) -> int:
        """Get retry count from explicit x-retry-count header."""
        headers = message.headers or {}
        return headers.get(RETRY_COUNT_HEADER, 0)

    async def _publish_to_retry(self, body: bytes, event: KnowledgeEvent, retry_key: str, next_retry_count: int) -> bool:
        """Publish message to a retry queue via the DLX. Returns True if published successfully."""
        try:
            # CRITICAL: Publish to the RETRY_DLX exchange (not default_exchange)
            # mandatory is a parameter of publish(), NOT Message()
            dlx = await self._channel.get_exchange(RETRY_DLX)
            await dlx.publish(
                aio_pika.Message(
                    body=body,
                    headers={
                        "eventType": event.event_type if event else None,
                        RETRY_COUNT_HEADER: next_retry_count,  # Increment retry count
                    },
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                ),
                routing_key=retry_key,
                mandatory=True,  # Ensure message is routed to a queue (raises if not)
            )
            return True
        except Exception as e:
            logger.error(f"Failed to publish retry message: {e}")
            return False

    async def _publish_to_dlq(self, body: bytes, event: KnowledgeEvent) -> bool:
        """Publish message to DLQ via the DLX. Returns True if published successfully."""
        try:
            dlx = await self._channel.get_exchange(RETRY_DLX)
            await dlx.publish(
                aio_pika.Message(
                    body=body,
                    headers={"eventType": event.event_type if event else None},
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                ),
                routing_key=FAILED_ROUTING_KEY,
                mandatory=True,  # Ensure message is routed to a queue (raises if not)
            )
            return True
        except Exception as e:
            logger.error(f"Failed to publish to DLQ: {e}")
            return False

    async def _nack_with_requeue(self, message: IncomingMessage):
        """NACK a message with requeue=True for immediate redelivery. Handles nack failure."""
        try:
            await message.nack(requeue=True)
        except Exception as e:
            logger.error(f"Failed to nack message, triggering connection reconnect: {e}")
            # CRITICAL: Do NOT call connection.close() — RobustConnection.close() sets
            # _close_called=True and cancels reconnection, permanently stopping consumption.
            # Instead, call reconnect() which properly re-establishes the connection,
            # recreates channel, topology, and consumer.
            try:
                if self._connection:
                    await self._connection.reconnect()
            except Exception as reconnectErr:
                logger.error(f"Failed to reconnect: {reconnectErr}")
