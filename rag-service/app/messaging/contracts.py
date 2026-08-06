"""
Message contracts for RabbitMQ communication.

Java sends camelCase JSON, Python uses snake_case.
Pydantic is configured to accept both via alias and populate_by_name.
"""
from pydantic import BaseModel, Field, ConfigDict
from typing import Optional


class KnowledgeEvent(BaseModel):
    """Events received from ingest-service via RabbitMQ.

    Java sends camelCase: eventId, eventType, knowledgeBaseId, etc.
    Python uses snake_case: event_id, event_type, knowledge_base_id, etc.
    """
    model_config = ConfigDict(
        populate_by_name=True,
        alias_generator=lambda x: ''.join(
            word.capitalize() if i > 0 else word
            for i, word in enumerate(x.split('_'))
        ),
    )

    event_id: str = Field(..., alias="eventId")
    event_type: str = Field(..., alias="eventType")
    aggregate_type: str = Field("ingest_task", alias="aggregateType")
    aggregate_id: str = Field(..., alias="aggregateId")
    knowledge_base_id: int = Field(..., alias="knowledgeBaseId")
    document_id: int = Field(..., alias="documentId")
    version_id: int = Field(1, alias="versionId")
    object_key: str = Field(..., alias="objectKey")
    file_name: str = Field("", alias="fileName")
    content_type: Optional[str] = Field(None, alias="contentType")
    operator_id: int = Field(..., alias="operatorId")
    schema_version: int = Field(1, alias="schemaVersion")
    occurred_at: str = Field(..., alias="occurredAt")
    trace_id: Optional[str] = Field(None, alias="traceId")

    def json_payload(self) -> str:
        """Serialize to JSON with camelCase for Java compatibility."""
        return self.model_dump_json(by_alias=True)


class IngestResultEvent(BaseModel):
    """Result events published back to ingest-service."""
    model_config = ConfigDict(
        populate_by_name=True,
        alias_generator=lambda x: ''.join(
            word.capitalize() if i > 0 else word
            for i, word in enumerate(x.split('_'))
        ),
    )

    event_id: str = Field(..., alias="eventId")
    event_type: str = Field(..., alias="eventType")
    aggregate_type: str = Field("rag_document", alias="aggregateType")
    aggregate_id: str = Field(..., alias="aggregateId")
    knowledge_base_id: int = Field(..., alias="knowledgeBaseId")
    document_id: int = Field(..., alias="documentId")
    version_id: int = Field(1, alias="versionId")
    status: str = Field(..., alias="status")
    chunk_count: Optional[int] = Field(None, alias="chunkCount")
    error_message: Optional[str] = Field(None, alias="errorMessage")
    operator_id: int = Field(..., alias="operatorId")
    schema_version: int = Field(1, alias="schemaVersion")
    occurred_at: str = Field(..., alias="occurredAt")
    trace_id: Optional[str] = Field(None, alias="traceId")

    def json_payload(self) -> str:
        """Serialize to JSON with camelCase for Java compatibility."""
        return self.model_dump_json(by_alias=True)
