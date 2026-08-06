"""
Tests for RAG service OutboxPublisher.
Verifies that the publisher uses the correct exchange and routing keys.
"""
import pytest
from unittest.mock import AsyncMock, MagicMock

import aio_pika
from app.messaging.publisher import OutboxPublisher
from app.messaging.topology import RAG_RESULT_EXCHANGE


@pytest.fixture
def publisher():
    """Create a publisher instance with mocked connection."""
    pub = OutboxPublisher()
    pub._connection = AsyncMock()
    pub._channel = AsyncMock()
    return pub


def test_derive_routing_key_completed():
    """Test routing key for completed events."""
    from app.messaging.publisher import OutboxPublisher
    assert OutboxPublisher._derive_routing_key("rag.document.ingest.completed") == "rag.result.succeeded"


def test_derive_routing_key_failed():
    """Test routing key for failed events."""
    from app.messaging.publisher import OutboxPublisher
    assert OutboxPublisher._derive_routing_key("rag.document.ingest.failed") == "rag.result.failed"


def test_derive_routing_key_deleted():
    """Test routing key for deleted events."""
    from app.messaging.publisher import OutboxPublisher
    assert OutboxPublisher._derive_routing_key("rag.document.delete.completed") == "rag.result.deleted"


def test_derive_routing_key_unknown():
    """Test routing key for unknown events."""
    from app.messaging.publisher import OutboxPublisher
    assert OutboxPublisher._derive_routing_key("unknown.event") == "rag.result.other"


def test_rag_result_exchange_value():
    """Test that RAG_RESULT_EXCHANGE has the correct value."""
    assert RAG_RESULT_EXCHANGE == "docbase.rag.result.exchange"


@pytest.mark.asyncio
async def test_publish_uses_correct_exchange(publisher):
    """Test that publisher declares and uses the RAG_RESULT_EXCHANGE."""
    # Mock the exchange
    mock_exchange = AsyncMock()
    publisher._channel.declare_exchange = AsyncMock(return_value=mock_exchange)

    # Create a mock event
    mock_event = MagicMock()
    mock_event.event_id = "test-event-123"
    mock_event.event_type = "rag.document.ingest.completed"
    mock_event.payload = '{"test": "data"}'
    mock_event.status = "PENDING"
    mock_event.claimed_at = None
    mock_event.published_at = None

    # Mock db session
    mock_db = MagicMock()
    mock_db.commit = MagicMock()

    # Publish
    await publisher._publish_event(mock_event, mock_db)

    # Verify the correct exchange was declared
    publisher._channel.declare_exchange.assert_called_once_with(
        RAG_RESULT_EXCHANGE,  # Should use dedicated RAG result exchange
        aio_pika.ExchangeType.TOPIC,  # Correct exchange type
        durable=True
    )

    # Verify publish was called with correct routing key
    mock_exchange.publish.assert_called_once()
    call_kwargs = mock_exchange.publish.call_args
    assert call_kwargs[1]["routing_key"] == "rag.result.succeeded"
    assert call_kwargs[1]["mandatory"] is True
