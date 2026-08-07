package com.docbase.knowledge.ingest;

import com.docbase.common.core.BusinessException;
import com.docbase.contracts.IngestEvent;
import com.docbase.knowledge.document.KnowledgeDocumentConstants;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes ingest status events from Ingest service and updates document ingest_status.
 *
 * <p>Listens on docbase.knowledge.status.queue (bound to docbase.document.exchange).
 * Events: ingest.document.processing / .dispatched / .succeeded / .failed / .deleted
 */
@Component
public class IngestStatusListener {

    private static final Logger log = LoggerFactory.getLogger(IngestStatusListener.class);

    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;

    public IngestStatusListener(KnowledgeDocumentMapper documentMapper, ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle ingest status events.
     */
    @RabbitListener(queues = "docbase.knowledge.status.queue")
    public void handleIngestStatus(@Payload String payload,
                                   @Header(value = "eventType", required = false) String eventType) {
        log.info("Received ingest status event: type={}", eventType);
        try {
            IngestEvent event = objectMapper.readValue(payload, IngestEvent.class);
            if (event.documentId() == null) {
                log.warn("Ingest status event missing documentId: {}", eventType);
                return;
            }
            updateIngestStatus(event);
        } catch (Exception e) {
            log.error("Failed to process ingest status event: type={} error={}", eventType, e.getMessage());
            throw new RuntimeException("Failed to process ingest status event", e);
        }
    }

    private void updateIngestStatus(IngestEvent event) {
        // Map by eventType (not ingestStatus string), because Ingest may change task status
        // to RETRY_WAIT/DEAD before publishing the event.
        Integer newStatus = mapEventTypeToIngestStatus(event.eventType());
        if (newStatus == null) {
            log.warn("Unknown ingest event type: {}", event.eventType());
            return;
        }
        int updated = documentMapper.updateIngestStatus(event.documentId(), newStatus);
        if (updated == 0) {
            log.warn("No document found to update ingest status: documentId={}", event.documentId());
        } else {
            log.info("Updated document {} ingest_status to {} (eventType={})",
                    event.documentId(), newStatus, event.eventType());
        }
    }

    /**
     * Map IngestEvent eventType to document.ingest_status int.
     * eventType is the source of truth (ingest.document.succeeded / .failed / .processing etc.)
     */
    private Integer mapEventTypeToIngestStatus(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "ingest.document.processing", "ingest.document.dispatched" ->
                    KnowledgeDocumentConstants.INGEST_STATUS_PROCESSING;
            case "ingest.document.succeeded" -> KnowledgeDocumentConstants.INGEST_STATUS_SUCCESS;
            case "ingest.document.failed" -> KnowledgeDocumentConstants.INGEST_STATUS_FAILED;
            case "ingest.document.deleted" -> null; // deletion handled separately
            default -> null;
        };
    }
}
