package com.docbase.knowledge.ingest;

import com.docbase.common.core.BusinessException;
import com.docbase.contracts.IngestEvent;
import com.docbase.knowledge.document.KnowledgeDocumentConstants;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for IngestStatusListener: verifies that ingest status events are correctly
 * mapped to document.ingest_status, including failure cases.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class IngestStatusListenerTest {

    @Autowired
    IngestStatusListener listener;

    @Autowired
    KnowledgeDocumentMapper documentMapper;

    @Autowired
    ObjectMapper objectMapper;

    private Long documentId;

    @BeforeEach
    void setUp() {
        // Create a test document directly via mapper
        com.docbase.knowledge.document.domain.KnowledgeDocument doc = new com.docbase.knowledge.document.domain.KnowledgeDocument();
        doc.setKnowledgeBaseId(1L);
        doc.setTitle("Test Doc " + System.nanoTime());
        doc.setStatus(2); // PUBLISHED
        doc.setIngestStatus(1); // PENDING
        doc.setCreatedBy(1L);
        doc.setDeleted(0);
        documentMapper.insert(doc);
        documentId = doc.getId();
    }

    @Test
    void failedEvent_mapsToFailedStatus() throws Exception {
        // Simulate ingest.document.failed event
        IngestEvent event = new IngestEvent(
                UUID.randomUUID(), "ingest.document.failed", "ingest_task",
                "task-123", 1L, documentId, "FAILED", 1L, 1, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        listener.handleIngestStatus(payload, "ingest.document.failed");

        // Verify document ingest_status is now FAILED (4)
        com.docbase.knowledge.document.domain.KnowledgeDocument updated = documentMapper.selectById(documentId);
        assertThat(updated.getIngestStatus()).isEqualTo(KnowledgeDocumentConstants.INGEST_STATUS_FAILED);
    }

    @Test
    void succeededEvent_mapsToSuccessStatus() throws Exception {
        IngestEvent event = new IngestEvent(
                UUID.randomUUID(), "ingest.document.succeeded", "ingest_task",
                "task-123", 1L, documentId, "SUCCEEDED", 1L, 1, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        listener.handleIngestStatus(payload, "ingest.document.succeeded");

        com.docbase.knowledge.document.domain.KnowledgeDocument updated = documentMapper.selectById(documentId);
        assertThat(updated.getIngestStatus()).isEqualTo(KnowledgeDocumentConstants.INGEST_STATUS_SUCCESS);
    }

    @Test
    void processingEvent_mapsToProcessingStatus() throws Exception {
        IngestEvent event = new IngestEvent(
                UUID.randomUUID(), "ingest.document.processing", "ingest_task",
                "task-123", 1L, documentId, "PROCESSING", 1L, 1, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        listener.handleIngestStatus(payload, "ingest.document.processing");

        com.docbase.knowledge.document.domain.KnowledgeDocument updated = documentMapper.selectById(documentId);
        assertThat(updated.getIngestStatus()).isEqualTo(KnowledgeDocumentConstants.INGEST_STATUS_PROCESSING);
    }

    @Test
    void retryWaitEvent_still_mapsToFailedViaEventType() throws Exception {
        // CRITICAL: Ingest may change task status to RETRY_WAIT before publishing.
        // We map by eventType, not ingestStatus string, so this should still work.
        IngestEvent event = new IngestEvent(
                UUID.randomUUID(), "ingest.document.failed", "ingest_task",
                "task-123", 1L, documentId,
                "RETRY_WAIT", // Task status is RETRY_WAIT, but eventType is still "failed"
                1L, 1, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        listener.handleIngestStatus(payload, "ingest.document.failed");

        com.docbase.knowledge.document.domain.KnowledgeDocument updated = documentMapper.selectById(documentId);
        assertThat(updated.getIngestStatus()).isEqualTo(KnowledgeDocumentConstants.INGEST_STATUS_FAILED);
    }
}
