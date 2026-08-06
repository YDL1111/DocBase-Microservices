package com.docbase.ingest.consumer;

import com.docbase.ingest.task.IngestTaskService;
import com.docbase.ingest.task.domain.RagResultEvent;
import com.docbase.ingest.task.mapper.RagResultEventMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for processing RAG result events.
 *
 * This is a separate service to ensure @Transactional works correctly
 * (Spring's transaction proxy doesn't apply to self-invocation).
 */
@Service
public class RagResultService {

    private static final Logger log = LoggerFactory.getLogger(RagResultService.class);

    private final RagResultEventMapper resultEventMapper;
    private final IngestTaskService taskService;

    public RagResultService(RagResultEventMapper resultEventMapper, IngestTaskService taskService) {
        this.resultEventMapper = resultEventMapper;
        this.taskService = taskService;
    }

    /**
     * Processes a result event idempotently within a transaction.
     * Uses database unique constraint on event_id for deduplication.
     *
     * @param resultEventId the unique event ID from RAG service
     * @param eventType the type of event (succeeded, failed, deleted)
     * @param taskId the ingest task ID
     * @param json the full event payload
     * @throws IllegalArgumentException if the event type is unknown (should go to DLQ)
     */
    @Transactional
    public void processResultEvent(String resultEventId, String eventType, Long taskId, JsonNode json) {
        // Check if already processed (idempotency)
        RagResultEvent existing = resultEventMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RagResultEvent>()
                        .eq("event_id", resultEventId)
        );

        if (existing != null) {
            log.info("Result event already processed, skipping: {}", resultEventId);
            return;
        }

        // Process based on event type
        if ("ingest.document.succeeded".equals(eventType) || "rag.document.ingest.completed".equals(eventType)) {
            int chunkCount = json.has("chunkCount") ? json.get("chunkCount").asInt() : 0;
            taskService.markSucceeded(taskId, chunkCount);
        } else if ("ingest.document.failed".equals(eventType) || "rag.document.ingest.failed".equals(eventType)) {
            String errorMessage = json.has("errorMessage") ? json.get("errorMessage").asText() : "Unknown error";
            // Schedule retry with backoff
            java.time.LocalDateTime nextRetry = java.time.LocalDateTime.now().plusMinutes(5);
            taskService.markFailed(taskId, errorMessage, nextRetry);
        } else if ("ingest.document.deleted".equals(eventType) || "rag.document.delete.completed".equals(eventType)) {
            taskService.markSucceeded(taskId, 0);
        } else {
            // Unknown event type - throw to send to DLQ
            throw new IllegalArgumentException("Unknown RAG result event type: " + eventType);
        }

        // Record the event as processed
        RagResultEvent event = new RagResultEvent();
        event.setEventId(resultEventId);
        event.setEventType(eventType);
        event.setTaskId(taskId);
        event.setResult("SUCCESS");
        event.setProcessedAt(java.time.LocalDateTime.now());
        resultEventMapper.insert(event);

        log.info("Result event processed: {} type={}", resultEventId, eventType);
    }
}
