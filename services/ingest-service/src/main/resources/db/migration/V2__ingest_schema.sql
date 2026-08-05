-- =============================================================================
-- V2: Ingest service core schema for docbase_ingest
-- Tables: ingest_task, consumed_event
-- Design notes:
--   - No cross-schema foreign keys
--   - event_id unique constraint for idempotent consumption
--   - High-performance indexes on status, event_id, document_id
--   - Compatible with MySQL 8.4
-- =============================================================================

CREATE TABLE ingest_task (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    event_id          CHAR(36)      NOT NULL COMMENT '关联事件ID(幂等键)',
    knowledge_base_id BIGINT        NOT NULL COMMENT '知识库ID',
    document_id       BIGINT        NOT NULL COMMENT '文档ID',
    object_key        VARCHAR(512)  NOT NULL DEFAULT '' COMMENT 'MinIO对象Key',
    task_type         VARCHAR(32)   NOT NULL COMMENT '任务类型 IMPORT/REIMPORT/DELETE',
    status            VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    attempt_count     INT           NOT NULL DEFAULT 0 COMMENT '尝试次数',
    last_error        VARCHAR(512)  NULL COMMENT '最后错误信息',
    next_retry_at     DATETIME      NULL COMMENT '下次重试时间',
    python_kb_id      VARCHAR(128)  NULL COMMENT 'Python RAG知识库ID',
    python_doc_id     VARCHAR(128)  NULL COMMENT 'Python RAG文档ID',
    chunk_count       INT           NULL COMMENT '切片数量',
    created_by        BIGINT        NOT NULL COMMENT '创建者用户ID(IAM)',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    started_at        DATETIME      NULL COMMENT '开始处理时间',
    finished_at       DATETIME      NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ingest_event_id (event_id),
    KEY idx_ingest_status (status),
    KEY idx_ingest_knowledge_base_id (knowledge_base_id),
    KEY idx_ingest_document_id (document_id),
    KEY idx_ingest_next_retry_at (next_retry_at),
    KEY idx_ingest_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '入库任务表';

CREATE TABLE consumed_event (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    event_id         CHAR(36)      NOT NULL COMMENT '事件ID(幂等键)',
    event_type       VARCHAR(128)  NOT NULL COMMENT '事件类型',
    schema_version   INT           NOT NULL DEFAULT 1 COMMENT '事件schema版本',
    consumed_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    result           VARCHAR(32)   NOT NULL DEFAULT 'SUCCESS' COMMENT '消费结果 SUCCESS/FAILED/REJECTED',
    error_message    VARCHAR(512)  NULL COMMENT '错误信息',
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumed_event_id (event_id),
    KEY idx_consumed_event_type (event_type),
    KEY idx_consumed_consumed_at (consumed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '已消费事件表（幂等去重）';
