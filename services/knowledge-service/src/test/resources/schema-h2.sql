-- H2-compatible schema for knowledge-service tests (MySQL mode)
-- This schema includes V2 + V3 changes (delete_marker for soft delete)
-- IMPORTANT: Includes UNIQUE constraints matching V3 to properly test delete/recreate scenarios

CREATE TABLE IF NOT EXISTS knowledge_base (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512) NOT NULL DEFAULT '',
    owner_id        BIGINT       NOT NULL,
    organization_id BIGINT,
    visibility      TINYINT      NOT NULL DEFAULT 1,
    status          TINYINT      NOT NULL DEFAULT 1,
    sort_num        INT          NOT NULL DEFAULT 0,
    created_by      BIGINT       NOT NULL,
    updated_by      BIGINT                ,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    delete_marker   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (name, delete_marker)
);

CREATE TABLE IF NOT EXISTS knowledge_member (
    id               BIGINT   AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT  NOT NULL,
    user_id          BIGINT   NOT NULL,
    member_role      TINYINT  NOT NULL DEFAULT 4,
    created_by       BIGINT   NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT  NOT NULL DEFAULT 0,
    delete_marker   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (knowledge_base_id, user_id, delete_marker)
);

CREATE TABLE IF NOT EXISTS knowledge_folder (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT      NOT NULL,
    parent_id        BIGINT       NOT NULL DEFAULT 0,
    name             VARCHAR(128) NOT NULL,
    sort_num         INT          NOT NULL DEFAULT 0,
    created_by       BIGINT       NOT NULL,
    updated_by       BIGINT                ,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    delete_marker   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (knowledge_base_id, parent_id, name, delete_marker)
);

CREATE TABLE IF NOT EXISTS knowledge_document (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT        NOT NULL,
    organization_id   BIGINT,
    folder_id         BIGINT        NOT NULL DEFAULT 0,
    title             VARCHAR(256)  NOT NULL,
    original_filename VARCHAR(512)  NOT NULL DEFAULT '',
    object_key        VARCHAR(512)  NOT NULL DEFAULT '',
    content_type      VARCHAR(128)  NOT NULL DEFAULT '',
    file_size         BIGINT        NOT NULL DEFAULT 0,
    checksum          VARCHAR(128)           ,
    ingest_status     TINYINT       NOT NULL DEFAULT 1,
    version           INT           NOT NULL DEFAULT 1,
    status            TINYINT       NOT NULL DEFAULT 1,
    visibility        TINYINT       NOT NULL DEFAULT 1,
    created_by        BIGINT        NOT NULL,
    updated_by        BIGINT                 ,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT       NOT NULL DEFAULT 0,
    delete_marker   BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS knowledge_document_version (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
    document_id       BIGINT        NOT NULL,
    version           INT           NOT NULL,
    original_filename VARCHAR(512)  NOT NULL DEFAULT '',
    object_key        VARCHAR(512)  NOT NULL DEFAULT '',
    content_type      VARCHAR(128)  NOT NULL DEFAULT '',
    file_size         BIGINT        NOT NULL DEFAULT 0,
    checksum          VARCHAR(128)           ,
    ingest_status     TINYINT       NOT NULL DEFAULT 1,
    version_remark    VARCHAR(512)           ,
    created_by        BIGINT        NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT       NOT NULL DEFAULT 0,
    delete_marker   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (document_id, version, delete_marker)
);

CREATE TABLE IF NOT EXISTS knowledge_document_acl (
    id               BIGINT   AUTO_INCREMENT PRIMARY KEY,
    document_id      BIGINT   NOT NULL,
    knowledge_base_id BIGINT  NOT NULL,
    subject_type     TINYINT  NOT NULL,
    subject_id       BIGINT   NOT NULL,
    permission_type  TINYINT  NOT NULL DEFAULT 1,
    created_by       BIGINT   NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT  NOT NULL DEFAULT 0,
    delete_marker   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (document_id, subject_type, subject_id, permission_type, delete_marker)
);

-- Lease timestamps are UTC LocalDateTime values, matching the MySQL V7 migration and application clock.
CREATE TABLE IF NOT EXISTS knowledge_upload_request (
    id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id   BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    client_request_id   VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    object_key          VARCHAR(512) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    document_id         BIGINT,
    lease_token         VARCHAR(64),
    lease_expires_at    TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (knowledge_base_id, user_id, client_request_id)
);

CREATE TABLE IF NOT EXISTS event_outbox (
    event_id        CHAR(36)     NOT NULL PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         CLOB         NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(512)          ,
    next_retry_at   TIMESTAMP             ,
    published_by    VARCHAR(128)          ,
    schema_version  INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP            ,
    claimed_at      TIMESTAMP
);
