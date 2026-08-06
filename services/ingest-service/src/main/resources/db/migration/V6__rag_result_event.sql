-- =============================================================================
-- V6: RAG result event table for idempotent consumption
-- =============================================================================

CREATE TABLE rag_result_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    event_id     CHAR(36)     NOT NULL COMMENT 'RAG结果事件ID(幂等键)',
    event_type   VARCHAR(128) NOT NULL COMMENT '事件类型',
    task_id      BIGINT       NOT NULL COMMENT '关联任务ID',
    result       VARCHAR(32)  NOT NULL DEFAULT 'SUCCESS' COMMENT '处理结果',
    processed_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '处理时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_result_event_id (event_id),
    KEY idx_rag_result_task_id (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RAG结果事件消费记录表（幂等去重）';
