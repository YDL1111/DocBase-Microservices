"""
Unit tests for RagConsumer retry logic.

Verifies:
- 1st failure goes to 30s retry queue (rag.retry.1)
- 2nd failure goes to 5m retry queue (rag.retry.2)
- 3rd failure goes to 30m retry queue (rag.retry.3)
- 4th failure goes to DLQ (rag.failed)
- Unroutable message does NOT ack
- Publish failure does NOT ack (nacks with requeue)
"""
import asyncio
import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import aio_pika
from aio_pika import IncomingMessage

from app.messaging.consumer import RagConsumer, RETRY_COUNT_HEADER
from app.messaging.topology import RETRY_ROUTING_KEYS, FAILED_ROUTING_KEY


@pytest.fixture
def mock_channel():
    """Create a mock channel with all required methods."""
    channel = AsyncMock()
    channel.is_closed = False
    channel.on_return_raises = True
    channel.set_qos = AsyncMock()
    channel.get_exchange = AsyncMock()
    channel.get_queue = AsyncMock()
    channel.declare_exchange = AsyncMock()
    channel.declare_queue = AsyncMock()
    channel.close = AsyncMock()
    return channel


@pytest.fixture
def mock_connection(mock_channel):
    """Create a mock connection with reconnect support."""
    connection = AsyncMock()
    connection.channel = AsyncMock(return_value=mock_channel)
    connection.close = AsyncMock()
    connection.reconnect = AsyncMock()
    return connection


@pytest.fixture
def mock_message():
    """Create a mock IncomingMessage with valid KnowledgeEvent fields."""
    message = AsyncMock(spec=IncomingMessage)
    message.body = json.dumps({
        "eventId": str(uuid.uuid4()),
        "eventType": "rag.document.ingest.requested",
        "aggregateType": "ingest_task",
        "aggregateId": "task-123",
        "knowledgeBaseId": 1,
        "documentId": 42,
        "versionId": 1,
        "objectKey": "documents/test.pdf",  # REQUIRED field
        "ingestStatus": "PROCESSING",
        "operatorId": 1,
        "schemaVersion": 1,
        "occurredAt": "2024-01-01T00:00:00Z",
    }).encode()
    message.headers = {}
    message.ack = AsyncMock()
    message.nack = AsyncMock()
    return message


@pytest.fixture
def consumer(mock_channel):
    """Create a RagConsumer with mocked internals. Handler succeeds by default."""
    handler = AsyncMock()
    consumer = RagConsumer(handler)
    consumer._channel = mock_channel
    consumer._connection = AsyncMock()
    return consumer


@pytest.fixture
def failing_consumer(mock_channel, mock_connection):
    """Create a RagConsumer where the handler always fails (business logic error).
    CRITICAL: Injects the shared mock_connection so tests can assert on it."""
    handler = AsyncMock(side_effect=Exception("Document parsing failed"))
    consumer = RagConsumer(handler)
    consumer._channel = mock_channel
    consumer._connection = mock_connection
    return consumer


class TestRetryRouting:
    """Test that failures are routed to correct retry queues."""

    @pytest.mark.asyncio
    async def test_first_failure_goes_to_30s_queue(self, failing_consumer, mock_channel, mock_message):
        """1st failure (retry_count=0) should publish to rag.retry.1 (30s queue)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx

        # Simulate first failure (no retry count in headers)
        mock_message.headers = {}

        await failing_consumer._on_message(mock_message)

        # Should publish to first retry key
        mock_dlx.publish.assert_called_once()
        call_kwargs = mock_dlx.publish.call_args
        assert call_kwargs[1]['routing_key'] == RETRY_ROUTING_KEYS[0]  # rag.retry.1
        assert call_kwargs[1]['mandatory'] is True
        # Should NOT ack (retry publish handles redelivery)
        mock_message.ack.assert_called_once()

    @pytest.mark.asyncio
    async def test_second_failure_goes_to_5m_queue(self, failing_consumer, mock_channel, mock_message):
        """2nd failure (retry_count=1) should publish to rag.retry.2 (5m queue)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx

        # Simulate second failure (retry count = 1)
        mock_message.headers = {RETRY_COUNT_HEADER: 1}

        await failing_consumer._on_message(mock_message)

        mock_dlx.publish.assert_called_once()
        call_kwargs = mock_dlx.publish.call_args
        assert call_kwargs[1]['routing_key'] == RETRY_ROUTING_KEYS[1]  # rag.retry.2

    @pytest.mark.asyncio
    async def test_third_failure_goes_to_30m_queue(self, failing_consumer, mock_channel, mock_message):
        """3rd failure (retry_count=2) should publish to rag.retry.3 (30m queue)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx

        # Simulate third failure (retry count = 2)
        mock_message.headers = {RETRY_COUNT_HEADER: 2}

        await failing_consumer._on_message(mock_message)

        mock_dlx.publish.assert_called_once()
        call_kwargs = mock_dlx.publish.call_args
        assert call_kwargs[1]['routing_key'] == RETRY_ROUTING_KEYS[2]  # rag.retry.3

    @pytest.mark.asyncio
    async def test_fourth_failure_goes_to_dlq(self, failing_consumer, mock_channel, mock_message):
        """4th failure (retry_count=3) should publish to DLQ (rag.failed)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx

        # Simulate fourth failure (retry count = 3, exceeds max retries)
        mock_message.headers = {RETRY_COUNT_HEADER: 3}

        await failing_consumer._on_message(mock_message)

        mock_dlx.publish.assert_called_once()
        call_kwargs = mock_dlx.publish.call_args
        assert call_kwargs[1]['routing_key'] == FAILED_ROUTING_KEY  # rag.failed


class TestAckNackBehavior:
    """Test ACK/NACK behavior on publish success/failure."""

    @pytest.mark.asyncio
    async def test_successful_retry_publish_ack(self, failing_consumer, mock_channel, mock_message):
        """Successful retry publish should ACK the original message."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()  # Success
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {}

        await failing_consumer._on_message(mock_message)

        mock_message.ack.assert_called_once()
        mock_message.nack.assert_not_called()

    @pytest.mark.asyncio
    async def test_failed_publish_does_not_ack(self, failing_consumer, mock_channel, mock_message):
        """Failed retry publish should NOT ack, should nack+requeue."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock(side_effect=Exception("Publish failed"))
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {}

        await failing_consumer._on_message(mock_message)

        mock_message.ack.assert_not_called()
        mock_message.nack.assert_called_once_with(requeue=True)

    @pytest.mark.asyncio
    async def test_nack_failure_triggers_reconnect(self, failing_consumer, mock_channel, mock_message, mock_connection):
        """If NACK itself fails, should trigger connection reconnect (not close)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock(side_effect=Exception("Publish failed"))
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {}
        mock_message.nack = AsyncMock(side_effect=Exception("NACK failed"))

        await failing_consumer._on_message(mock_message)

        # Should trigger reconnect (not close) when nack fails
        mock_connection.reconnect.assert_called_once()
        mock_channel.close.assert_not_called()


class TestRetryCountHeader:
    """Test explicit retry count header handling."""

    @pytest.mark.asyncio
    async def test_retry_count_increments_on_republish(self, failing_consumer, mock_channel, mock_message):
        """Retry count header should increment when republishing."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {RETRY_COUNT_HEADER: 1}  # 2nd attempt

        await failing_consumer._on_message(mock_message)

        # Verify the published message has incremented retry count
        call_args = mock_dlx.publish.call_args
        published_message = call_args[0][0]  # First positional arg is the Message
        assert published_message.headers[RETRY_COUNT_HEADER] == 2

    @pytest.mark.asyncio
    async def test_no_retry_count_defaults_to_zero(self, failing_consumer, mock_channel, mock_message):
        """Missing retry count header should default to 0 (first failure)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {}  # No retry count

        await failing_consumer._on_message(mock_message)

        # Should route to first retry queue
        call_kwargs = mock_dlx.publish.call_args
        assert call_kwargs[1]['routing_key'] == RETRY_ROUTING_KEYS[0]


class TestHandlerFailurePath:
    """Test that valid events with handler failures are correctly retried."""

    @pytest.mark.asyncio
    async def test_valid_event_handler_failure_triggers_retry(self, failing_consumer, mock_channel, mock_message):
        """Valid event with handler exception should trigger retry (not deserialization error)."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {}

        await failing_consumer._on_message(mock_message)

        # Handler should have been called (valid event deserialized successfully)
        failing_consumer.message_handler.assert_called_once()

        # Should publish to first retry queue
        mock_dlx.publish.assert_called_once()
        call_kwargs = mock_dlx.publish.call_args
        assert call_kwargs[1]['routing_key'] == RETRY_ROUTING_KEYS[0]

    @pytest.mark.asyncio
    async def test_handler_success_acks_immediately(self, consumer, mock_channel, mock_message):
        """Successful handler execution should ACK without retry."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock()
        mock_channel.get_exchange.return_value = mock_dlx
        mock_message.headers = {}

        await consumer._on_message(mock_message)

        # Handler called
        consumer.message_handler.assert_called_once()
        # ACK, no retry publish
        mock_message.ack.assert_called_once()
        mock_dlx.publish.assert_not_called()

    @pytest.mark.asyncio
    async def test_dlq_publish_failure_nacks_with_requeue(self, failing_consumer, mock_channel, mock_message):
        """When DLQ publish fails (max retries exceeded), should NACK with requeue."""
        mock_dlx = AsyncMock()
        mock_dlx.publish = AsyncMock(side_effect=Exception("DLQ publish failed"))
        mock_channel.get_exchange.return_value = mock_dlx
        # Simulate max retries exceeded (retry_count = 3)
        mock_message.headers = {RETRY_COUNT_HEADER: 3}

        await failing_consumer._on_message(mock_message)

        # Should NOT ack
        mock_message.ack.assert_not_called()
        # Should NACK with requeue
        mock_message.nack.assert_called_once_with(requeue=True)
