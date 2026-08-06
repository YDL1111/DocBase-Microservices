-- =============================================================================
-- V4: RAG outbox for ingest-service to publish RAG request events
-- This table stores events that will be consumed by rag-service via RabbitMQ.
-- =============================================================================

CREATE TABLE rag_outbox (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    event_id          CHAR(36)     NOT NULL COMMENT '事件ID(幂等键)',
    event_type        VARCHAR(128) NOT NULL COMMENT '事件类型',
    aggregate_type    VARCHAR(64)  NOT NULL COMMENT '聚合类型',
    aggregate_id      VARCHAR(64)  NOT NULL COMMENT '聚合ID',
    knowledge_base_id BIGINT       NOT NULL COMMENT '知识库ID',
    document_id       BIGINT       NOT NULL COMMENT '文档ID',
    payload           TEXT         NOT NULL COMMENT '事件JSON负载',
    status            VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    retry_count       INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    last_error        VARCHAR(512)          NULL COMMENT '最后错误',
    next_retry_at     DATETIME              NULL COMMENT '下次重试时间',
    claimed_at        DATETIME              NULL COMMENT '认领时间',
    published_by      VARCHAR(128)          NULL COMMENT '发布者实例',
    schema_version    INT          NOT NULL DEFAULT 1 COMMENT '事件schema版本',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    published_at      DATETIME              NULL COMMENT '发布时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_outbox_event_id (event_id),
    KEY idx_rag_outbox_status_retry (status, next_retry_at),
    KEY idx_rag_outbox_claimed_at (claimed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RAG事件Outbox表';
