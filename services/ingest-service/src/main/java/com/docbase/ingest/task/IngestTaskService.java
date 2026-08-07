package com.docbase.ingest.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.contracts.IngestEvent;
import com.docbase.contracts.KnowledgeEvent;
import com.docbase.contracts.RagEvent;
import com.docbase.ingest.event.IngestEventPublisher;
import com.docbase.ingest.task.domain.ConsumedEvent;
import com.docbase.ingest.task.domain.IngestTask;
import com.docbase.ingest.task.domain.RagOutboxEntity;
import com.docbase.ingest.task.mapper.ConsumedEventMapper;
import com.docbase.ingest.task.mapper.IngestTaskMapper;
import com.docbase.ingest.task.mapper.RagOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing ingest tasks.
 * Implements task state machine, retry logic, and idempotent event processing.
 */
@Service
public class IngestTaskService {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskService.class);

    private final IngestTaskMapper ingestTaskMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final RagOutboxMapper ragOutboxMapper;
    private final IngestEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${docbase.ingest.max-retries:3}")
    private int maxRetries;

    public IngestTaskService(IngestTaskMapper ingestTaskMapper,
                              ConsumedEventMapper consumedEventMapper,
                              RagOutboxMapper ragOutboxMapper,
                              IngestEventPublisher eventPublisher,
                              ObjectMapper objectMapper) {
        this.ingestTaskMapper = ingestTaskMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.ragOutboxMapper = ragOutboxMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes a knowledge event idempotently within a transaction.
     * Creates an ingest task and records consumption atomically.
     *
     * @param event the knowledge event to process
     * @return true if processed successfully, false if already consumed
     */
    @Transactional
    public boolean processEvent(KnowledgeEvent event) {
        // Check if already consumed
        ConsumedEvent existing = consumedEventMapper.selectOne(
                new QueryWrapper<ConsumedEvent>().eq("event_id", event.eventId().toString())
        );

        if (existing != null) {
            log.info("Event already consumed, skipping: {}", event.eventId());
            return false;
        }

        // Create ingest task based on event type
        IngestTask task = createTaskForEvent(event);

        // Record consumption (unique constraint on event_id prevents duplicates)
        ConsumedEvent consumed = new ConsumedEvent();
        consumed.setEventId(event.eventId().toString());
        consumed.setEventType(event.eventType());
        consumed.setSchemaVersion(event.schemaVersion());
        consumed.setConsumedAt(LocalDateTime.now());
        consumed.setResult("SUCCESS");
        consumedEventMapper.insert(consumed);

        log.info("Created ingest task: eventId={}, taskId={}, type={}",
                event.eventId(), task.getId(), task.getTaskType());

        return true;
    }

    /**
     * Creates an ingest task based on the event type.
     */
    private IngestTask createTaskForEvent(KnowledgeEvent event) {
        IngestTask task = new IngestTask();
        task.setEventId(event.eventId().toString());
        task.setKnowledgeBaseId(event.knowledgeBaseId());
        task.setDocumentId(event.documentId());
        task.setObjectKey(event.objectKey());
        task.setVersionId(event.versionId());
        task.setFileName(event.fileName());
        task.setContentType(event.contentType());
        task.setStatus(IngestTaskStatus.PENDING.name());
        task.setAttemptCount(0);
        task.setCreatedBy(event.operatorId());

        // Determine task type from event type
        if (KnowledgeEvent.DOCUMENT_REGISTERED.equals(event.eventType())) {
            task.setTaskType(IngestTaskType.IMPORT.name());
        } else if (KnowledgeEvent.REINGEST_REQUESTED.equals(event.eventType())) {
            task.setTaskType(IngestTaskType.REIMPORT.name());
        } else if (KnowledgeEvent.DOCUMENT_DELETED.equals(event.eventType())) {
            task.setTaskType(IngestTaskType.DELETE.name());
        } else {
            // Unknown event type - this should not happen if deserializer validates
            throw new IllegalArgumentException("Unknown event type: " + event.eventType());
        }

        ingestTaskMapper.insert(task);
        return task;
    }

    /**
     * Finds pending tasks ready for processing.
     */
    public List<IngestTask> findPendingTasks(int limit) {
        return ingestTaskMapper.selectList(
                new QueryWrapper<IngestTask>()
                        .eq("status", IngestTaskStatus.PENDING.name())
                        .orderByAsc("created_at")
                        .last("LIMIT " + limit)
        );
    }

    /**
     * Finds tasks ready for retry.
     */
    public List<IngestTask> findRetryTasks(int limit) {
        return ingestTaskMapper.selectList(
                new QueryWrapper<IngestTask>()
                        .eq("status", IngestTaskStatus.RETRY_WAIT.name())
                        .le("next_retry_at", LocalDateTime.now())
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + limit)
        );
    }

    /**
     * Transitions a task to PROCESSING status.
     * Supports both PENDING and RETRY_WAIT states.
     * Uses conditional update to prevent concurrent processing.
     */
    @Transactional
    public boolean startProcessing(Long taskId) {
        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }
        String status = task.getStatus();
        // Only PENDING or RETRY_WAIT can transition to PROCESSING
        if (!IngestTaskStatus.PENDING.name().equals(status) &&
            !IngestTaskStatus.RETRY_WAIT.name().equals(status)) {
            return false;
        }

        int updated = ingestTaskMapper.update(null,
                new UpdateWrapper<IngestTask>()
                        .eq("id", taskId)
                        .eq("status", status) // Conditional on current status
                        .set("status", IngestTaskStatus.PROCESSING.name())
                        .set("started_at", LocalDateTime.now())
                        .set("attempt_count", (task.getAttemptCount() != null ? task.getAttemptCount() : 0) + 1)
        );
        return updated > 0;
    }

    /**
     * Marks a task as DISPATCHED (sent to RAG).
     */
    @Transactional
    public void markDispatched(Long taskId, String pythonKbId, String pythonDocId) {
        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
        }
        validateStatusTransition(task, IngestTaskStatus.DISPATCHED);

        task.setStatus(IngestTaskStatus.DISPATCHED.name());
        task.setPythonKbId(pythonKbId);
        task.setPythonDocId(pythonDocId);
        ingestTaskMapper.updateById(task);

        // Publish status event
        publishStatusEvent(task, IngestEvent.DOCUMENT_DISPATCHED);
    }

    /**
     * Cancels a task.
     */
    @Transactional
    public void cancelTask(Long taskId) {
        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
        }
        if (task.getStatus().equals(IngestTaskStatus.SUCCEEDED.name()) ||
            task.getStatus().equals(IngestTaskStatus.DEAD.name())) {
            throw new BusinessException("INVALID_STATUS", "Cannot cancel terminal status: " + task.getStatus());
        }

        task.setStatus(IngestTaskStatus.CANCELLED.name());
        task.setFinishedAt(LocalDateTime.now());
        ingestTaskMapper.updateById(task);
    }

    /**
     * Retries a failed task manually.
     */
    @Transactional
    public void retryTask(Long taskId) {
        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
        }
        if (!task.getStatus().equals(IngestTaskStatus.FAILED.name()) &&
            !task.getStatus().equals(IngestTaskStatus.DEAD.name())) {
            throw new BusinessException("INVALID_STATUS", "Only failed tasks can be retried");
        }

        task.setStatus(IngestTaskStatus.PENDING.name());
        task.setLastError(null);
        task.setNextRetryAt(null);
        ingestTaskMapper.updateById(task);
    }

    /**
     * Gets a task by ID.
     */
    public IngestTask getById(Long taskId) {
        return ingestTaskMapper.selectById(taskId);
    }

    /**
     * Lists tasks with pagination.
     */
    public Page<IngestTask> listTasks(long current, long size, String status) {
        Page<IngestTask> page = new Page<>(current, size);
        QueryWrapper<IngestTask> wrapper = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        return ingestTaskMapper.selectPage(page, wrapper);
    }

    private void validateStatusTransition(IngestTask task, IngestTaskStatus target) {
        IngestTaskStatus current = IngestTaskStatus.valueOf(task.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Cannot transition from " + current + " to " + target);
        }
    }

    /**
     * Publishes a RAG ingest request event to the outbox.
     * The task transitions to DISPATCHED only after the event is successfully written.
     */
    @Transactional
    public void publishRagIngestRequest(IngestTask task) {
        // Generate single eventId for both outbox and payload
        String eventId = UUID.randomUUID().toString();

        // Build RAG ingest request payload with real data
        String payload = buildRagEventPayload(task, RagEvent.INGEST_REQUESTED, eventId);

        // Write to outbox (same transaction as status update)
        RagOutboxEntity outboxEvent = new RagOutboxEntity();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType(RagEvent.INGEST_REQUESTED);
        outboxEvent.setAggregateType("ingest_task");
        outboxEvent.setAggregateId(task.getId().toString());
        outboxEvent.setKnowledgeBaseId(task.getKnowledgeBaseId());
        outboxEvent.setDocumentId(task.getDocumentId());
        outboxEvent.setPayload(payload);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setRetryCount(0);
        outboxEvent.setSchemaVersion(RagEvent.CURRENT_SCHEMA_VERSION);
        outboxEvent.setCreatedAt(LocalDateTime.now());
        ragOutboxMapper.insert(outboxEvent);

        // Note: Task stays in PROCESSING until RagOutboxPublisher confirms publish
        // The publisher will transition to DISPATCHED after confirm
        log.info("Written RAG ingest request to outbox for task: {}, eventId: {}", task.getId(), eventId);
    }

    /**
     * Publishes a RAG delete request event to the outbox.
     */
    @Transactional
    public void publishRagDeleteRequest(IngestTask task) {
        // Generate single eventId for both outbox and payload
        String eventId = UUID.randomUUID().toString();

        // Build RAG delete request payload with real data
        String payload = buildRagEventPayload(task, RagEvent.DELETE_REQUESTED, eventId);

        RagOutboxEntity outboxEvent = new RagOutboxEntity();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType(RagEvent.DELETE_REQUESTED);
        outboxEvent.setAggregateType("ingest_task");
        outboxEvent.setAggregateId(task.getId().toString());
        outboxEvent.setKnowledgeBaseId(task.getKnowledgeBaseId());
        outboxEvent.setDocumentId(task.getDocumentId());
        outboxEvent.setPayload(payload);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setRetryCount(0);
        outboxEvent.setSchemaVersion(RagEvent.CURRENT_SCHEMA_VERSION);
        outboxEvent.setCreatedAt(LocalDateTime.now());
        ragOutboxMapper.insert(outboxEvent);

        log.info("Written RAG delete request to outbox for task: {}, eventId: {}", task.getId(), eventId);
    }

    /**
     * Called by RagOutboxPublisher after successful RabbitMQ confirm.
     * Completes the dispatch in a single transaction:
     * 1. Mark RagOutbox as PUBLISHED (conditional update)
     * 2. Transition task from PROCESSING to DISPATCHED
     * 3. Write ingest.document.dispatched status event to Outbox
     *
     * @param eventId the RagOutbox event ID
     * @param taskId the task ID (aggregateId in RagOutbox)
     */
    @Transactional
    public void completeRagDispatch(String eventId, Long taskId) {
        // Mark Outbox as PUBLISHED first (conditional update from PUBLISHING)
        int updated = ragOutboxMapper.markPublished(eventId);
        if (updated == 0) {
            log.warn("RagOutbox not in PUBLISHING state, skipping: {}", eventId);
            return;
        }

        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
        }

        // Validate status transition
        validateStatusTransition(task, IngestTaskStatus.DISPATCHED);

        // Update task status
        task.setStatus(IngestTaskStatus.DISPATCHED.name());
        ingestTaskMapper.updateById(task);

        // Write status event to ingest Outbox
        publishStatusEvent(task, IngestEvent.DOCUMENT_DISPATCHED);

        log.info("RagOutbox published and task dispatched: eventId={}, taskId={}", eventId, taskId);
    }

    /**
     * Marks a task as SUCCEEDED after RAG processing completes.
     * Idempotent - safe to call multiple times for the same event.
     */
    @Transactional
    public void markSucceeded(Long taskId, Integer chunkCount) {
        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
        }

        // Idempotent - if already SUCCEEDED, skip
        if (IngestTaskStatus.SUCCEEDED.name().equals(task.getStatus())) {
            log.info("Task already SUCCEEDED, skipping: {}", taskId);
            return;
        }

        validateStatusTransition(task, IngestTaskStatus.SUCCEEDED);

        task.setStatus(IngestTaskStatus.SUCCEEDED.name());
        task.setChunkCount(chunkCount);
        task.setFinishedAt(LocalDateTime.now());
        task.setLastError(null);
        ingestTaskMapper.updateById(task);

        // Publish status event
        publishStatusEvent(task, IngestEvent.DOCUMENT_SUCCEEDED);
    }

    /**
     * Marks a task as FAILED and schedules retry.
     * Idempotent - safe to call multiple times for the same event.
     */
    @Transactional
    public void markFailed(Long taskId, String error, LocalDateTime nextRetryAt) {
        IngestTask task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
        }

        // Idempotent - if already in terminal state, skip
        if (IngestTaskStatus.SUCCEEDED.name().equals(task.getStatus()) ||
            IngestTaskStatus.DEAD.name().equals(task.getStatus())) {
            log.info("Task in terminal state, skipping: {} status={}", taskId, task.getStatus());
            return;
        }

        validateStatusTransition(task, IngestTaskStatus.FAILED);

        task.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        task.setFinishedAt(LocalDateTime.now());

        // Determine if should retry
        int attemptCount = task.getAttemptCount() != null ? task.getAttemptCount() : 0;
        task.setAttemptCount(attemptCount + 1);

        if (attemptCount + 1 >= maxRetries || nextRetryAt == null) {
            task.setStatus(IngestTaskStatus.DEAD.name());
        } else {
            task.setStatus(IngestTaskStatus.RETRY_WAIT.name());
            task.setNextRetryAt(nextRetryAt);
        }

        ingestTaskMapper.updateById(task);

        // Publish status event
        publishStatusEvent(task, IngestEvent.DOCUMENT_FAILED);
    }

    /**
     * Builds the JSON payload for a RAG event using ObjectMapper.
     * Uses camelCase to match Python Pydantic contract.
     * eventId is passed in to ensure consistency between outbox and payload.
     */
    String buildRagEventPayload(IngestTask task, String eventType, String eventId) {
        try {
            RagEventPayload payload = new RagEventPayload();
            payload.setEventId(eventId);  // Use same eventId as outbox
            payload.setEventType(eventType);
            payload.setAggregateType("ingest_task");
            payload.setAggregateId(task.getId().toString());
            payload.setKnowledgeBaseId(task.getKnowledgeBaseId());
            payload.setDocumentId(task.getDocumentId());
            payload.setVersionId(task.getVersionId() != null ? task.getVersionId() : 1L);
            payload.setObjectKey(task.getObjectKey());
            payload.setFileName(task.getFileName() != null ? task.getFileName() : "");
            payload.setContentType(task.getContentType() != null ? task.getContentType() : "");
            payload.setOperatorId(task.getCreatedBy());
            payload.setSchemaVersion(RagEvent.CURRENT_SCHEMA_VERSION);
            payload.setOccurredAt(Instant.now().toString());

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize RAG event payload", e);
        }
    }

    /**
     * Publishes a status feedback event to the outbox.
     * This is called within the same transaction as the task status update.
     * If event writing fails, the entire transaction rolls back to maintain consistency.
     */
    private void publishStatusEvent(IngestTask task, String eventType) {
        IngestEvent event = new IngestEvent(
                UUID.randomUUID(),
                eventType,
                "ingest_task",
                task.getId().toString(),
                task.getKnowledgeBaseId(),
                task.getDocumentId(),
                task.getStatus(),
                task.getCreatedBy(),
                IngestEvent.CURRENT_SCHEMA_VERSION,
                Instant.now()
        );
        // Let exceptions propagate to rollback the transaction
        eventPublisher.writeEvent(event);
    }
}
