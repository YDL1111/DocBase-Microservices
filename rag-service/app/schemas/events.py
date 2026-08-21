"""
Event schemas for RAG service messaging.
"""
from pydantic import BaseModel, Field
from typing import Optional
from datetime import datetime


class RagIngestRequest(BaseModel):
    """Event received from ingest-service requesting RAG processing."""
    event_id: str
    event_type: str
    aggregate_type: str
    aggregate_id: str
    knowledge_base_id: int
    document_id: int
    version_id: int = 1
    object_key: str
    file_name: str
    content_type: Optional[str] = None
    operator_id: int
    schema_version: int = 1
    occurred_at: datetime
    trace_id: Optional[str] = None


class RagResultEvent(BaseModel):
    """Event published by RAG service with processing result."""
    event_id: str
    event_type: str
    aggregate_type: str = "rag_document"
    aggregate_id: str
    knowledge_base_id: int
    document_id: int
    version_id: int = 1
    status: str  # SUCCEEDED, FAILED
    chunk_count: Optional[int] = None
    error_message: Optional[str] = None
    operator_id: int
    schema_version: int = 1
    occurred_at: datetime
    trace_id: Optional[str] = None


class KnowledgeScope(BaseModel):
    knowledge_base_id: int
    visible_document_ids: list[int]


class ChatRequest(BaseModel):
    """Internal chat request with visible document filtering."""
    query: str
    knowledge_scopes: list[KnowledgeScope] = Field(default_factory=list)
    # Backward-compatible single-knowledge-base fields.
    knowledge_base_id: Optional[int] = None
    visible_document_ids: list[int] = Field(default_factory=list)
    session_id: Optional[str] = None


class RetrieveRequest(BaseModel):
    """Internal retrieval request."""
    query: str
    knowledge_base_id: int
    visible_document_ids: list[int]
    top_k: Optional[int] = None
