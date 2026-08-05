package com.docbase.ingest.task;

import com.docbase.ingest.task.domain.IngestTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

/**
 * Periodically processes pending and retry-ready ingest tasks.
 *
 * This is the core processing loop that:
 * 1. Picks up PENDING tasks
 * 2. Transitions them to PROCESSING
 * 3. Dispatches to RAG (placeholder)
 * 4. Handles failures and retries
 */
@Component
public class IngestTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskProcessor.class);

    private final IngestTaskService taskService;

    @Value("${docbase.ingest.batch-size:10}")
    private int batchSize;

    @Value("${docbase.ingest.max-retries:3}")
    private int maxRetries;

    @Value("${docbase.ingest.retry-delays:PT30S,PT5M,PT30M}")
    private String[] retryDelays;

    public IngestTaskProcessor(IngestTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * Process pending tasks at fixed interval.
     */
    @Scheduled(fixedDelayString = "${docbase.ingest.poll-interval-ms:5000}")
    public void processPendingTasks() {
        List<IngestTask> pendingTasks = taskService.findPendingTasks(batchSize);
        if (pendingTasks.isEmpty()) {
            return;
        }

        log.debug("Processing {} pending tasks", pendingTasks.size());

        for (IngestTask task : pendingTasks) {
            processTask(task);
        }
    }

    /**
     * Process retry-ready tasks at fixed interval.
     */
    @Scheduled(fixedDelayString = "${docbase.ingest.retry-poll-interval-ms:30000}")
    public void processRetryTasks() {
        List<IngestTask> retryTasks = taskService.findRetryTasks(batchSize);
        if (retryTasks.isEmpty()) {
            return;
        }

        log.debug("Processing {} retry tasks", retryTasks.size());

        for (IngestTask task : retryTasks) {
            processTask(task);
        }
    }

    /**
     * Processes a single task.
     */
    private void processTask(IngestTask task) {
        Long taskId = task.getId();

        // Try to claim the task for processing
        if (!taskService.startProcessing(taskId)) {
            return; // Already claimed by another instance
        }

        try {
            if (IngestTaskType.DELETE.name().equals(task.getTaskType())) {
                processDeleteTask(task);
            } else {
                processImportTask(task);
            }
        } catch (Exception e) {
            log.error("Task processing failed: taskId={}", taskId, e);
            handleTaskFailure(taskId, e.getMessage());
        }
    }

    /**
     * Processes an import/reimport task.
     *
     * NOTE: This is a placeholder for RAG integration. In future, this would:
     * 1. Download file from MinIO using objectKey
     * 2. Call RAG service to parse and vectorize
     * 3. Update pythonKbId and pythonDocId
     *
     * For now, the task stays in PROCESSING state until RAG integration is complete.
     * We do NOT fabricate DISPATCHED or SUCCEEDED status without actual RAG processing.
     */
    private void processImportTask(IngestTask task) {
        log.info("Processing import task: taskId={}, documentId={}", task.getId(), task.getDocumentId());

        // TODO: Implement actual RAG integration
        // For now, mark as FAILED with a clear message indicating RAG is not yet integrated
        // This prevents false "success" status
        throw new UnsupportedOperationException(
                "RAG integration not yet implemented. Task cannot be completed: " + task.getId());
    }

    /**
     * Processes a delete task.
     *
     * NOTE: This is a placeholder for RAG integration. In future, this would call RAG to delete vectors.
     *
     * For now, the task stays in PROCESSING state until RAG integration is complete.
     * We do NOT fabricate SUCCEEDED status without actual RAG processing.
     */
    private void processDeleteTask(IngestTask task) {
        log.info("Processing delete task: taskId={}, documentId={}", task.getId(), task.getDocumentId());

        // TODO: Implement actual RAG integration
        // For now, mark as FAILED with a clear message indicating RAG is not yet integrated
        throw new UnsupportedOperationException(
                "RAG integration not yet implemented. Delete task cannot be completed: " + task.getId());
    }

    /**
     * Handles task failure with retry logic.
     */
    private void handleTaskFailure(Long taskId, String error) {
        IngestTask task = taskService.getById(taskId);
        if (task == null) {
            return;
        }

        int attemptCount = task.getAttemptCount() != null ? task.getAttemptCount() : 0;
        LocalDateTime nextRetryAt = null;

        if (attemptCount < maxRetries) {
            // Calculate next retry time
            Duration delay = attemptCount < retryDelays.length ?
                    Duration.parse(retryDelays[attemptCount]) :
                    Duration.parse(retryDelays[retryDelays.length - 1]);
            nextRetryAt = LocalDateTime.now().plus(delay);
        }

        taskService.markFailed(taskId, error, nextRetryAt);
    }
}
