-- =============================================================================
-- V3: Ingest service outbox for status feedback events
-- =============================================================================

CREATE TABLE ingest_outbox (
    event_id          CHAR(36)      NOT NULL PRIMARY KEY,
    aggregate_type    VARCHAR(64)   NOT NULL,
    aggregate_id      VARCHAR(64)   NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    payload           TEXT          NOT NULL,
    status            VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    retry_count       INT           NOT NULL DEFAULT 0,
    last_error        VARCHAR(512)  NULL,
    next_retry_at     DATETIME      NULL,
    published_by      VARCHAR(128)  NULL,
    schema_version    INT           NOT NULL DEFAULT 1,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at      DATETIME      NULL,
    claimed_at        DATETIME      NULL,
    KEY idx_ingest_outbox_status_retry (status, next_retry_at, created_at),
    KEY idx_ingest_outbox_claimed_at (claimed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Ingest service outbox for status feedback events';
