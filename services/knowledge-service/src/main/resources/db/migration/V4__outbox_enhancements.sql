-- =============================================================================
-- V4: Enhance event_outbox for reliable publishing
-- Adds retry tracking and publisher instance identification
-- =============================================================================

-- Add retry tracking columns
ALTER TABLE event_outbox
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    ADD COLUMN last_error VARCHAR(512) NULL COMMENT '最后错误信息',
    ADD COLUMN next_retry_at DATETIME NULL COMMENT '下次重试时间',
    ADD COLUMN published_by VARCHAR(128) NULL COMMENT '发布实例标识',
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1 COMMENT '事件schema版本';

-- Add index for efficient polling
CREATE INDEX idx_event_outbox_status_retry ON event_outbox (status, next_retry_at, created_at);
