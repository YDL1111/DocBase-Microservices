package com.docbase.knowledge.document;

/**
 * Constants for knowledge document status, visibility, ingest status, and ACL types.
 * Centralized to avoid magic numbers across services.
 */
public interface KnowledgeDocumentConstants {

    // --- document.status ---
    int STATUS_DRAFT = 1;
    int STATUS_PUBLISHED = 2;
    int STATUS_ARCHIVED = 3;

    // --- document.visibility ---
    int VISIBILITY_PRIVATE = 1;
    int VISIBILITY_DEPT = 2;
    int VISIBILITY_PUBLIC = 3;

    // --- document.ingest_status ---
    int INGEST_STATUS_PENDING = 1;
    int INGEST_STATUS_PROCESSING = 2;
    int INGEST_STATUS_SUCCESS = 3;
    int INGEST_STATUS_FAILED = 4;

    // --- knowledge_document_acl.subject_type ---
    int ACL_SUBJECT_TYPE_USER = 1;
    int ACL_SUBJECT_TYPE_DEPT = 2;

    // --- knowledge_document_acl.permission_type ---
    int ACL_PERMISSION_VIEW = 1;
    int ACL_PERMISSION_EDIT = 2;
    int ACL_PERMISSION_MANAGE = 3;

    /**
     * Maximum number of document IDs returned for AI chat visibility queries.
     * RAG caps visible_document_ids at 1000; we query one extra to detect truncation.
     */
    int VISIBLE_DOC_IDS_LIMIT = 1000;
}
