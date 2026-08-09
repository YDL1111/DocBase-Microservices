-- Recoverable upload leases. V6 rows that were uploading before this migration are
-- immediately eligible for one safe atomic recovery attempt. Application lease timestamps
-- are UTC LocalDateTime values, so use MySQL's UTC clock rather than the session time zone.
ALTER TABLE knowledge_upload_request
    ADD COLUMN lease_token VARCHAR(64) NULL AFTER document_id,
    ADD COLUMN lease_expires_at DATETIME NULL AFTER lease_token;

UPDATE knowledge_upload_request
SET lease_expires_at = UTC_TIMESTAMP()
WHERE status = 'UPLOADING' AND lease_expires_at IS NULL;

CREATE INDEX idx_knowledge_upload_request_lease
    ON knowledge_upload_request (status, lease_expires_at);
