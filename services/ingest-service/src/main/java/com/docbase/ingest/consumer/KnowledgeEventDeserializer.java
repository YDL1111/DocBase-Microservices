package com.docbase.ingest.consumer;

import com.docbase.contracts.KnowledgeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Deserializes KnowledgeEvent from RabbitMQ message payload.
 * Validates required fields and schema version.
 */
@Component
public class KnowledgeEventDeserializer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEventDeserializer.class);

    private final ObjectMapper objectMapper;

    public KnowledgeEventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Deserializes and validates a KnowledgeEvent from JSON payload.
     *
     * @param payload JSON payload from RabbitMQ
     * @return validated KnowledgeEvent
     * @throws EventValidationException if payload is invalid
     */
    public KnowledgeEvent deserialize(String payload) throws EventValidationException {
        if (payload == null || payload.isBlank()) {
            throw new EventValidationException("Empty payload");
        }

        try {
            var node = objectMapper.readTree(payload);

            // Validate required fields
            String eventId = getRequiredString(node, "eventId");
            String eventType = getRequiredString(node, "eventType");
            String aggregateType = getRequiredString(node, "aggregateType");
            String aggregateId = getRequiredString(node, "aggregateId");
            Long knowledgeBaseId = getRequiredLong(node, "knowledgeBaseId");
            Long documentId = getRequiredLong(node, "documentId");

            // objectKey is required for document events
            String objectKey = null;
            if (eventType.startsWith("knowledge.document.")) {
                objectKey = getRequiredString(node, "objectKey");
            }

            // Validate UUID format
            UUID.fromString(eventId);

            int schemaVersion = node.has("schemaVersion") ? node.get("schemaVersion").asInt() : 1;
            if (schemaVersion > KnowledgeEvent.CURRENT_SCHEMA_VERSION) {
                throw new EventValidationException("Unsupported schema version: " + schemaVersion);
            }

            Long operatorId = node.has("operatorId") && !node.get("operatorId").isNull() ?
                    node.get("operatorId").asLong() : null;

            Instant occurredAt = node.has("occurredAt") && !node.get("occurredAt").isNull() ?
                    Instant.parse(node.get("occurredAt").asText()) : Instant.now();

            return new KnowledgeEvent(
                    UUID.fromString(eventId),
                    eventType,
                    aggregateType,
                    aggregateId,
                    knowledgeBaseId,
                    documentId,
                    objectKey,
                    operatorId,
                    schemaVersion,
                    occurredAt
            );

        } catch (EventValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new EventValidationException("Failed to parse event: " + e.getMessage(), e);
        }
    }

    private String getRequiredString(com.fasterxml.jackson.databind.JsonNode node, String field) throws EventValidationException {
        if (!node.has(field) || node.get(field).isNull() || node.get(field).asText().isBlank()) {
            throw new EventValidationException("Missing required field: " + field);
        }
        return node.get(field).asText();
    }

    private Long getRequiredLong(com.fasterxml.jackson.databind.JsonNode node, String field) throws EventValidationException {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new EventValidationException("Missing required field: " + field);
        }
        return node.get(field).asLong();
    }

    /**
     * Exception for event validation failures.
     */
    public static class EventValidationException extends Exception {
        public EventValidationException(String message) {
            super(message);
        }
        public EventValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
