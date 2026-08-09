-- Durable multipart upload idempotency. Completed rows are retained (not soft deleted),
-- so the non-null unique key remains a permanent concurrency boundary for a client request.
CREATE TABLE knowledge_upload_request (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    knowledge_base_id   BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    client_request_id   VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    object_key          VARCHAR(512) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    document_id         BIGINT       NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_upload_request_idempotency (knowledge_base_id, user_id, client_request_id),
    KEY idx_knowledge_upload_request_document (document_id),
    KEY idx_knowledge_upload_request_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Knowledge multipart upload idempotency requests';
