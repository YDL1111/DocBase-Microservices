package com.docbase.ingest.task;

/**
 * Ingest task type enumeration.
 */
public enum IngestTaskType {
    /** First-time import */
    IMPORT,
    /** Re-import (update existing) */
    REIMPORT,
    /** Manual retry */
    RETRY,
    /** Delete synchronization */
    DELETE
}
