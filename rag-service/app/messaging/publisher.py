"""
RabbitMQ publisher for RAG service outbox.
"""
import json
import uuid
from datetime import datetime

import aio_pika

from app.core.config import settings
from app.core.logging import get_logger
from app.db.models import RagOutbox
from app.messaging.topology import RAG_RESULT_EXCHANGE

logger = get_logger(__name__)


class OutboxPublisher:
    """Publishes events from the outbox table to RabbitMQ."""

    def __init__(self):
        self._connection = None
        self._channel = None

    async def start(self):
        """Establish RabbitMQ connection."""
        # Use individual connection fields to avoid URL-encoding issues with vhost
        self._connection = await aio_pika.connect_robust(
            host=settings.RABBITMQ_HOST,
            port=settings.RABBITMQ_PORT,
            login=settings.RABBITMQ_USER,
            password=settings.RABBITMQ_PASSWORD,
            virtualhost=settings.RABBITMQ_VHOST,
        )
        self._channel = await self._connection.channel()
        await self._channel.set_qos(prefetch_count=1)
        logger.info("Outbox publisher connected to RabbitMQ")

    async def stop(self):
        """Close RabbitMQ connection."""
        if self._connection:
            await self._connection.close()

    async def publish_pending_events(self, db_session):
        """
        Poll the outbox table and publish pending events.

        This method should be called periodically by a background task.
        """
        from sqlalchemy import and_, or_

        # Find events ready for publishing
        pending = db_session.query(RagOutbox).filter(
            and_(
                RagOutbox.status.in_(["PENDING", "FAILED"]),
                or_(
                    RagOutbox.next_retry_at.is_(None),
                    RagOutbox.next_retry_at <= datetime.utcnow(),
                ),
            )
        ).limit(10).all()

        for event in pending:
            await self._publish_event(event, db_session)

    async def _publish_event(self, event: RagOutbox, db_session):
        """Publish a single event with confirmation."""
        try:
            # Claim the event
            event.status = "PUBLISHING"
            event.claimed_at = datetime.utcnow()
            db_session.commit()

            # Declare dedicated RAG result exchange (separate from Ingest status events)
            exchange = await self._channel.declare_exchange(
                RAG_RESULT_EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True
            )

            # Build message
            message_body = event.payload.encode("utf-8")

            # Publish with confirmation
            await exchange.publish(
                aio_pika.Message(
                    body=message_body,
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    message_id=event.event_id,
                    content_type="application/json",
                ),
                routing_key=self._derive_routing_key(event.event_type),
                mandatory=True,  # Ensure message is routed
            )

            # Mark as published
            event.status = "PUBLISHED"
            event.published_at = datetime.utcnow()
            db_session.commit()

            logger.info(f"Published event: {event.event_id}")

        except Exception as e:
            logger.error(f"Failed to publish event {event.event_id}: {e}")
            # Mark as failed with retry
            event.status = "FAILED"
            event.retry_count += 1
            event.last_error = str(e)[:500]
            event.next_retry_at = datetime.utcnow()  # Will be updated with backoff
            db_session.commit()

    @staticmethod
    def _derive_routing_key(event_type: str) -> str:
        """
        Derive routing key from event type.
        Uses dedicated 'rag.result.*' prefix to avoid conflicting with Ingest status events.
        """
        if event_type == "rag.document.ingest.completed":
            return "rag.result.succeeded"
        elif event_type == "rag.document.ingest.failed":
            return "rag.result.failed"
        elif event_type == "rag.document.delete.completed":
            return "rag.result.deleted"
        return "rag.result.other"
