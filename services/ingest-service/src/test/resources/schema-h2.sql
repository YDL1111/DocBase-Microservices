-- H2-compatible schema for ingest-service tests (MySQL mode)
CREATE TABLE IF NOT EXISTS ingest_task (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
    event_id          CHAR(36)      NOT NULL,
    knowledge_base_id BIGINT        NOT NULL,
    document_id       BIGINT        NOT NULL,
    object_key        VARCHAR(512)  NOT NULL DEFAULT '',
    task_type         VARCHAR(32)   NOT NULL,
    status            VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    attempt_count     INT           NOT NULL DEFAULT 0,
    last_error        VARCHAR(512)  ,
    next_retry_at     TIMESTAMP     ,
    python_kb_id      VARCHAR(128)  ,
    python_doc_id     VARCHAR(128)  ,
    chunk_count       INT           ,
    created_by        BIGINT        NOT NULL,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at        TIMESTAMP     ,
    finished_at       TIMESTAMP     ,
    UNIQUE (event_id)
);

CREATE TABLE IF NOT EXISTS consumed_event (
    id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
    event_id         CHAR(36)      NOT NULL,
    event_type       VARCHAR(128)  NOT NULL,
    schema_version   INT           NOT NULL DEFAULT 1,
    consumed_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result           VARCHAR(32)   NOT NULL DEFAULT 'SUCCESS',
    error_message    VARCHAR(512)  ,
    UNIQUE (event_id)
);

CREATE TABLE IF NOT EXISTS ingest_outbox (
    event_id          CHAR(36)      NOT NULL PRIMARY KEY,
    aggregate_type    VARCHAR(64)   NOT NULL,
    aggregate_id      VARCHAR(64)   NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    payload           CLOB          NOT NULL,
    status            VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    retry_count       INT           NOT NULL DEFAULT 0,
    last_error        VARCHAR(512)  ,
    next_retry_at     TIMESTAMP     ,
    published_by      VARCHAR(128)  ,
    schema_version    INT           NOT NULL DEFAULT 1,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at      TIMESTAMP     ,
    claimed_at        TIMESTAMP     ,
    UNIQUE (event_id)
);
