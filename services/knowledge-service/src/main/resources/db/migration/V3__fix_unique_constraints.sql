-- =============================================================================
-- V3: Fix unique constraint issue with soft delete
--
-- Problem: UNIQUE(..., deleted) only allows one deleted record with the same name.
-- When a record is deleted (deleted=1), a new record with the same name can be
-- created (deleted=0). But if the new record is also deleted, it conflicts with
-- the old deleted record.
--
-- Solution: Use delete_marker column (BIGINT NOT NULL DEFAULT 0).
-- - Active records have delete_marker = 0 (unique, enforced by unique key)
-- - Deleted records have delete_marker = their own primary key ID (non-zero, unique)
-- - Unique key includes delete_marker, so active records are unique,
--   but multiple deleted records with the same name are allowed.
--
-- This approach works because:
-- - delete_marker is NOT NULL, so no NULL uniqueness issues
-- - Active records all have marker=0, enforcing uniqueness
-- - Each deleted record gets a unique marker value (its own ID)
-- =============================================================================

-- ============================================================
-- knowledge_base
-- ============================================================
ALTER TABLE knowledge_base
    ADD COLUMN delete_marker BIGINT NOT NULL DEFAULT 0 COMMENT '删除标记 0表示未删除 非0表示已删除(值为记录ID)';

-- Update delete_marker for records already marked as deleted
UPDATE knowledge_base SET delete_marker = id WHERE deleted = 1;

-- Drop old unique key and create new one with delete_marker
ALTER TABLE knowledge_base DROP INDEX uk_knowledge_base_name_deleted;
ALTER TABLE knowledge_base ADD UNIQUE KEY uk_knowledge_base_name_marker (name, delete_marker);

-- ============================================================
-- knowledge_member
-- ============================================================
ALTER TABLE knowledge_member
    ADD COLUMN delete_marker BIGINT NOT NULL DEFAULT 0 COMMENT '删除标记 0表示未删除 非0表示已删除(值为记录ID)';

UPDATE knowledge_member SET delete_marker = id WHERE deleted = 1;

ALTER TABLE knowledge_member DROP INDEX uk_knowledge_member_deleted;
ALTER TABLE knowledge_member ADD UNIQUE KEY uk_knowledge_member_marker (knowledge_base_id, user_id, delete_marker);

-- ============================================================
-- knowledge_folder
-- ============================================================
ALTER TABLE knowledge_folder
    ADD COLUMN delete_marker BIGINT NOT NULL DEFAULT 0 COMMENT '删除标记 0表示未删除 非0表示已删除(值为记录ID)';

UPDATE knowledge_folder SET delete_marker = id WHERE deleted = 1;

ALTER TABLE knowledge_folder DROP INDEX uk_knowledge_folder_name_deleted;
ALTER TABLE knowledge_folder ADD UNIQUE KEY uk_knowledge_folder_marker (knowledge_base_id, parent_id, name, delete_marker);

-- ============================================================
-- knowledge_document
-- ============================================================
ALTER TABLE knowledge_document
    ADD COLUMN delete_marker BIGINT NOT NULL DEFAULT 0 COMMENT '删除标记 0表示未删除 非0表示已删除(值为记录ID)';

UPDATE knowledge_document SET delete_marker = id WHERE deleted = 1;

-- Document doesn't have a name unique constraint, but add marker for consistency

-- ============================================================
-- knowledge_document_version
-- ============================================================
ALTER TABLE knowledge_document_version
    ADD COLUMN delete_marker BIGINT NOT NULL DEFAULT 0 COMMENT '删除标记 0表示未删除 非0表示已删除(值为记录ID)';

UPDATE knowledge_document_version SET delete_marker = id WHERE deleted = 1;

ALTER TABLE knowledge_document_version DROP INDEX uk_knowledge_doc_version_deleted;
ALTER TABLE knowledge_document_version ADD UNIQUE KEY uk_knowledge_doc_version_marker (document_id, version, delete_marker);

-- ============================================================
-- knowledge_document_acl
-- ============================================================
ALTER TABLE knowledge_document_acl
    ADD COLUMN delete_marker BIGINT NOT NULL DEFAULT 0 COMMENT '删除标记 0表示未删除 非0表示已删除(值为记录ID)';

UPDATE knowledge_document_acl SET delete_marker = id WHERE deleted = 1;

ALTER TABLE knowledge_document_acl DROP INDEX uk_knowledge_doc_acl_deleted;
ALTER TABLE knowledge_document_acl ADD UNIQUE KEY uk_knowledge_doc_acl_marker (document_id, subject_type, subject_id, permission_type, delete_marker);
