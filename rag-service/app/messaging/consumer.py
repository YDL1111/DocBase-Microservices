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
)

logger = get_logger(__name__)


class RagConsumer:
    """Consumes knowledge events from RabbitMQ."""

    def __init__(self, message_handler: Callable[[KnowledgeEvent], Awaitable[None]]):
        self.message_handler = message_handler
        self._connection = None
        self._channel = None

    async def start(self):
        """Start consuming messages."""
        self._connection = await aio_pika.connect_robust(settings.RABBITMQ_URL)
        self._channel = await self._connection.channel()

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

        # Retry DLX
        dlx = await self._channel.declare_exchange(
            RETRY_DLX, aio_pika.ExchangeType.DIRECT, durable=True
        )

        # Main queue with DLX
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

        # Retry queues - each binds to a unique routing key
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
                    "x-dead-letter-routing-key": "document.retry",
                },
            )
            # Each retry queue binds to its own unique routing key
            await retry_queue.bind(dlx, routing_key=RETRY_ROUTING_KEYS[i])

        # Main queue also binds to document.retry for retry redelivery
        await queue.bind(exchange, routing_key="document.retry")

        # DLQ
        dlq = await self._channel.declare_queue(DLQ_QUEUE, durable=True)
        await dlq.bind(dlx, routing_key=FAILED_ROUTING_KEY)

    async def _on_message(self, message: IncomingMessage):
        """Process incoming message with manual ACK."""
        async with message.process():
            try:
                body = json.loads(message.body.decode())
                event = KnowledgeEvent(**body)

                logger.info(f"Received event: {event.event_id} type={event.event_type}")

                # Process the event
                await self.message_handler(event)

                # Message is automatically ACKed by message.process() context manager

            except Exception as e:
                logger.error(f"Failed to process message: {e}")
                # Message will NOT be ACKed - it will be routed to DLX
                raise
