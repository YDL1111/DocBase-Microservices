package com.docbase.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Event contract for ingest-service domain events.
 * Published when ingest task status changes.
 *
 * @param eventId        unique event identifier
 * @param eventType      type of event (e.g., ingest.document.processing)
 * @param aggregateType  type of aggregate (ingest_task)
 * @param aggregateId    ID of the ingest task
 * @param knowledgeBaseId ID of the owning knowledge base
 * @param documentId     document ID
 * @param ingestStatus   current ingest status
 * @param operatorId     user ID who triggered the event
 * @param schemaVersion  event schema version
 * @param occurredAt     timestamp when the event occurred
 */
public record IngestEvent(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Long knowledgeBaseId,
        Long documentId,
        String ingestStatus,
        Long operatorId,
        int schemaVersion,
        Instant occurredAt
) {
    /** Current schema version for ingest events. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Event type constants. */
    public static final String DOCUMENT_PROCESSING = "ingest.document.processing";
    public static final String DOCUMENT_DISPATCHED = "ingest.document.dispatched";
    public static final String DOCUMENT_SUCCEEDED = "ingest.document.succeeded";
    public static final String DOCUMENT_FAILED = "ingest.document.failed";
    public static final String DOCUMENT_DELETED = "ingest.document.deleted";
}
