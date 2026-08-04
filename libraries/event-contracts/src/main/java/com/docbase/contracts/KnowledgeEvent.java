package com.docbase.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Event contract for knowledge-service domain events.
 * Published when knowledge base, folder, or document changes occur.
 *
 * @param eventId        unique event identifier
 * @param eventType      type of event (e.g., knowledge.base.created)
 * @param aggregateType  type of aggregate (knowledge_base, folder, document)
 * @param aggregateId    ID of the aggregate that generated the event
 * @param knowledgeBaseId ID of the owning knowledge base
 * @param documentId     document ID (if applicable)
 * @param objectKey      MinIO object key (if applicable)
 * @param operatorId     user ID who triggered the event
 * @param schemaVersion  event schema version for forward compatibility
 * @param occurredAt     timestamp when the event occurred
 */
public record KnowledgeEvent(
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
    /** Current schema version for knowledge events. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Event type constants. */
    public static final String BASE_CREATED = "knowledge.base.created";
    public static final String BASE_DELETED = "knowledge.base.deleted";
    public static final String DOCUMENT_REGISTERED = "knowledge.document.registered";
    public static final String DOCUMENT_DELETED = "knowledge.document.deleted";
    public static final String REINGEST_REQUESTED = "knowledge.document.reingest-requested";
}
