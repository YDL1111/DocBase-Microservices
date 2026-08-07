-- =============================================================================
-- V2: Chat service schema for docbase_chat
-- Tables: ai_chat_session (alter V1 baseline), ai_chat_message (new)
--
-- Design notes:
--   - No cross-schema foreign keys (chat-service owns only docbase_chat)
--   - Soft delete via deleted flag (0 = active, 1 = deleted)
--   - client_request_id unique constraint prevents duplicate user messages on client retry
--   - Reasonable indexes for user-scoped queries and session lookups
--   - Compatible with MySQL 8.4
--   - V1 baseline (ai_chat_session with id/user_id/title/created_at/updated_at) is
--     ALTERED (not dropped) to preserve existing session data on upgrade.
-- =============================================================================

-- Alter the V1 baseline table to add the new columns instead of dropping it.
ALTER TABLE ai_chat_session
    ADD COLUMN knowledge_base_id BIGINT NULL COMMENT '关联知识库ID(可选)' AFTER user_id,
    ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1活跃 2归档' AFTER title,
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除' AFTER updated_at,
    ADD KEY idx_chat_session_deleted (deleted);

-- Create the message table (did not exist in V1).
CREATE TABLE ai_chat_message (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    session_id          BIGINT        NOT NULL COMMENT '所属会话ID',
    user_id             BIGINT        NOT NULL COMMENT '所属用户ID(IAM)',
    role                TINYINT       NOT NULL COMMENT '角色 1用户 2助手 3系统',
    content             MEDIUMTEXT    NULL COMMENT '消息内容',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '状态 1流式输出中 2已完成 3失败 4已取消',
    client_request_id   VARCHAR(64)   NULL COMMENT '客户端幂等键(同一请求重试不重复写)',
    sources_json        MEDIUMTEXT    NULL COMMENT '来源信息JSON(RAG返回)',
    error_code          VARCHAR(64)   NULL COMMENT '安全错误码(仅失败时)',
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    completed_at        DATETIME(6)   NULL COMMENT '完成时间(流式输出结束)',
    deleted             TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_message_client_request_id (client_request_id, deleted),
    KEY idx_chat_message_session_created (session_id, created_at),
    KEY idx_chat_message_user (user_id),
    KEY idx_chat_message_status (status),
    KEY idx_chat_message_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI会话消息表';
