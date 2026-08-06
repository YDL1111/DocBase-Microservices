-- =============================================================================
-- V5: Add versionId, fileName, contentType columns to ingest_task
-- These fields are needed for proper RAG integration.
-- =============================================================================

ALTER TABLE ingest_task
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1 COMMENT '文档版本ID' AFTER document_id,
    ADD COLUMN file_name VARCHAR(512) NOT NULL DEFAULT '' COMMENT '原始文件名' AFTER object_key,
    ADD COLUMN content_type VARCHAR(128) NOT NULL DEFAULT '' COMMENT '文件MIME类型' AFTER file_name;

-- Add index for version lookups
CREATE INDEX idx_ingest_task_version ON ingest_task (knowledge_base_id, document_id, version_id);
