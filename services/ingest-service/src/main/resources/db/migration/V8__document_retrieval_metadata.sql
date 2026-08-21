-- Persist document metadata across ingest retries before forwarding it to RAG.
ALTER TABLE ingest_task
    ADD COLUMN document_title VARCHAR(255) NULL AFTER content_type,
    ADD COLUMN folder_id BIGINT NULL AFTER document_title,
    ADD COLUMN visibility INT NULL AFTER folder_id,
    ADD COLUMN document_created_at TIMESTAMP(6) NULL AFTER visibility,
    ADD COLUMN document_updated_at TIMESTAMP(6) NULL AFTER document_created_at;

CREATE INDEX idx_ingest_task_folder ON ingest_task (knowledge_base_id, folder_id);
