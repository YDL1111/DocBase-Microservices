-- H2-compatible schema for chat-service tests (MySQL mode)
-- Mirrors V2__chat_schema.sql constraints needed by the mappers and service logic.

CREATE TABLE IF NOT EXISTS ai_chat_session (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    knowledge_base_id BIGINT     ,
    title           VARCHAR(255) NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted         TINYINT      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id                  BIGINT        AUTO_INCREMENT PRIMARY KEY,
    session_id          BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    role                TINYINT       NOT NULL,
    content             CLOB          ,
    status              TINYINT       NOT NULL DEFAULT 1,
    client_request_id   VARCHAR(64)   ,
    sources_json        CLOB          ,
    error_code          VARCHAR(64)   ,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at        TIMESTAMP(6)  ,
    deleted             TINYINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ai_chat_session_knowledge_base (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id        BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    UNIQUE (session_id, knowledge_base_id)
);
