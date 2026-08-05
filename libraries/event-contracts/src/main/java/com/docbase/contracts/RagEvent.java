package com.docbase.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Event contract for RAG service events (placeholder for future implementation).
 * These events define the boundary between ingest-service and rag-service.
 *
 * @param eventId        unique event identifier
 * @param eventType      type of event
 * @param aggregateType  type of aggregate
 * @param aggregateId    ID of the aggregate
 * @param knowledgeBaseId ID of the owning knowledge base
 * @param documentId     document ID
 * @param objectKey      MinIO object key
 * @param operatorId     user ID who triggered the event
 * @param schemaVersion  event schema version
 * @param occurredAt     timestamp when the event occurred
 */
public record RagEvent(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Long knowledgeBaseId,
        Long documentId,
        String objectKey,
        Long operatorId,
        int schemaVersion,
        Instant occurredAt
) {
    /** Current schema version for RAG events. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Event type constants - placeholder for future RAG implementation. */
    public static final String INGEST_REQUESTED = "rag.document.ingest.requested";
    public static final String DELETE_REQUESTED = "rag.document.delete.requested";
    public static final String INGEST_COMPLETED = "rag.document.ingest.completed";
    public static final String INGEST_FAILED = "rag.document.ingest.failed";
}
