-- Organization snapshot used for department visibility. NULL remains fail-closed for legacy rows.
ALTER TABLE knowledge_base ADD COLUMN organization_id BIGINT NULL AFTER owner_id;
ALTER TABLE knowledge_document ADD COLUMN organization_id BIGINT NULL AFTER knowledge_base_id;
CREATE INDEX idx_knowledge_base_organization ON knowledge_base (organization_id, visibility, status);
CREATE INDEX idx_knowledge_document_organization ON knowledge_document (knowledge_base_id, organization_id, visibility);
