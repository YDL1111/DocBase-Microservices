"""
Tests for logging configuration.
Verifies that third-party loggers without trace_id/event_id don't raise KeyError.
"""
import logging


class TestTraceIdFilter:
    """Test that missing trace_id/event_id are filled with '-'."""

    def test_filter_fills_missing_trace_id(self):
        """Records without trace_id should get '-' instead of KeyError."""
        from app.core.logging import TraceIdFilter

        filter_obj = TraceIdFilter()

        record = logging.LogRecord(
            name="test.logger",
            level=logging.INFO,
            pathname="test.py",
            lineno=1,
            msg="Test message",
            args=(),
            exc_info=None,
        )
        # record has no trace_id or event_id

        result = filter_obj.filter(record)

        assert result is True
        assert record.trace_id == "-"
        assert record.event_id == "-"

    def test_filter_preserves_existing_trace_id(self):
        """Records with trace_id should keep their value."""
        from app.core.logging import TraceIdFilter

        filter_obj = TraceIdFilter()

        record = logging.LogRecord(
            name="test.logger",
            level=logging.INFO,
            pathname="test.py",
            lineno=1,
            msg="Test message",
            args=(),
            exc_info=None,
        )
        record.trace_id = "abc-123"
        record.event_id = "evt-456"

        result = filter_obj.filter(record)

        assert result is True
        assert record.trace_id == "abc-123"
        assert record.event_id == "evt-456"

    def test_third_party_logger_no_keyerror(self):
        """Third-party loggers without extra fields should not raise KeyError."""
        import io
        import logging as stdlib_logging

        from app.core.logging import setup_logging

        setup_logging()

        # Capture log output
        log_stream = io.StringIO()
        handler = stdlib_logging.StreamHandler(log_stream)
        handler.setFormatter(
            stdlib_logging.Formatter(
                "%(asctime)s | %(name)s | trace_id=%(trace_id)s event_id=%(event_id)s | %(message)s"
            )
        )
        from app.core.logging import TraceIdFilter
        handler.addFilter(TraceIdFilter())

        third_party_logger = stdlib_logging.getLogger("chromadb.test")
        third_party_logger.handlers = [handler]
        third_party_logger.setLevel(stdlib_logging.INFO)

        # This should NOT raise KeyError
        third_party_logger.info("Test message from third-party logger")

        output = log_stream.getvalue()
        assert "trace_id=-" in output
        assert "event_id=-" in output
        assert "Test message from third-party logger" in output
