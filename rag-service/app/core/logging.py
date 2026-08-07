"""
Structured logging configuration.
"""
import logging
import sys

from app.core.config import settings


class TraceIdFilter(logging.Filter):
    """Fill missing trace_id/event_id with '-' for log records that don't have them."""

    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "trace_id"):
            record.trace_id = "-"
        if not hasattr(record, "event_id"):
            record.event_id = "-"
        return True


def setup_logging():
    """Configure structured logging for the application."""
    log_format = (
        "%(asctime)s | %(levelname)-8s | %(name)s | "
        "trace_id=%(trace_id)s event_id=%(event_id)s | %(message)s"
    )

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(log_format))
    # Add filter to fill missing trace_id/event_id
    handler.addFilter(TraceIdFilter())

    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO))
    root_logger.handlers = [handler]

    # Suppress noisy loggers
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("chromadb").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    """Get a logger instance."""
    return logging.getLogger(name)
