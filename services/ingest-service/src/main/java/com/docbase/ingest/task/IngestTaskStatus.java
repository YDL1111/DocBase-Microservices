package com.docbase.ingest.task;

/**
 * Ingest task status enumeration.
 *
 * State machine:
 * PENDING -> PROCESSING -> DISPATCHED -> SUCCEEDED
 *                  |            |
 *                  v            v
 *              FAILED       FAILED
 *                  |
 *                  v
 *             RETRY_WAIT (auto-retry)
 *                  |
 *                  v
 *              DEAD (max retries exceeded)
 *
 * CANCELLED can be reached from PENDING or RETRY_WAIT.
 */
public enum IngestTaskStatus {
    /** Initial state, waiting to be processed */
    PENDING,
    /** Currently being processed */
    PROCESSING,
    /** Dispatched to RAG, waiting for completion */
    DISPATCHED,
    /** Successfully completed */
    SUCCEEDED,
    /** Failed, will be retried */
    FAILED,
    /** Waiting for next retry */
    RETRY_WAIT,
    /** Permanently failed after max retries */
    DEAD,
    /** Cancelled by user or system */
    CANCELLED;

    /**
     * Checks if the transition from this status to the target status is valid.
     */
    public boolean canTransitionTo(IngestTaskStatus target) {
        switch (this) {
            case PENDING:
                return target == PROCESSING || target == CANCELLED;
            case PROCESSING:
                return target == DISPATCHED || target == SUCCEEDED || target == FAILED;
            case DISPATCHED:
                return target == SUCCEEDED || target == FAILED;
            case FAILED:
                return target == RETRY_WAIT || target == DEAD || target == CANCELLED;
            case RETRY_WAIT:
                return target == PENDING || target == CANCELLED;
            case SUCCEEDED:
            case DEAD:
            case CANCELLED:
                return false; // Terminal states
            default:
                return false;
        }
    }

    /**
     * Checks if this is a terminal state.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD || this == CANCELLED;
    }
}
