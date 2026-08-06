"""
RAG document model - tracks document metadata and processing status.
"""
from datetime import datetime
from sqlalchemy import Column, BigInteger, String, Integer, DateTime, Text, Index

from app.db.session import Base


class RagDocument(Base):
    """Tracks document ingestion status in the RAG service."""
    __tablename__ = "rag_document"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    event_id = Column(String(36), unique=True, nullable=False, index=True)
    knowledge_base_id = Column(BigInteger, nullable=False, index=True)
    document_id = Column(BigInteger, nullable=False, index=True)
    version_id = Column(BigInteger, nullable=False, default=1)
    object_key = Column(String(512), nullable=False)
    file_name = Column(String(512), nullable=False)
    content_type = Column(String(128))
    content_hash = Column(String(128))
    chroma_collection = Column(String(128))
    chunk_count = Column(Integer, default=0)
    status = Column(String(24), nullable=False, default="PENDING")
    error_message = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    __table_args__ = (
        Index("idx_rag_doc_kb_version", "knowledge_base_id", "document_id", "version_id"),
    )


class ConsumedEvent(Base):
    """Tracks consumed events for idempotency."""
    __tablename__ = "rag_consumed_event"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    event_id = Column(String(36), unique=True, nullable=False, index=True)
    event_type = Column(String(128), nullable=False)
    schema_version = Column(Integer, default=1)
    result = Column(String(32), default="SUCCESS")
    error_message = Column(Text)
    consumed_at = Column(DateTime, default=datetime.utcnow)


class RagOutbox(Base):
    """Outbox for publishing RAG status events."""
    __tablename__ = "rag_outbox"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    event_id = Column(String(36), unique=True, nullable=False, index=True)
    event_type = Column(String(128), nullable=False)
    aggregate_type = Column(String(64), nullable=False)
    aggregate_id = Column(String(64), nullable=False)
    knowledge_base_id = Column(BigInteger, nullable=False)
    document_id = Column(BigInteger, nullable=False)
    payload = Column(Text, nullable=False)
    status = Column(String(24), nullable=False, default="PENDING")
    retry_count = Column(Integer, default=0)
    last_error = Column(String(512))
    next_retry_at = Column(DateTime)
    claimed_at = Column(DateTime)
    published_by = Column(String(128))
    schema_version = Column(Integer, default=1)
    created_at = Column(DateTime, default=datetime.utcnow)
    published_at = Column(DateTime)

    __table_args__ = (
        Index("idx_rag_outbox_status", "status", "next_retry_at"),
        Index("idx_rag_outbox_claimed", "claimed_at"),
    )
