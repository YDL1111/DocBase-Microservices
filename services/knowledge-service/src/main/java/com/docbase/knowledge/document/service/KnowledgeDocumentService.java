package com.docbase.knowledge.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.contracts.KnowledgeEvent;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.document.KnowledgeDocumentConstants;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentAclMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentVersionMapper;
import com.docbase.knowledge.document.mapper.KnowledgeUploadRequestMapper;
import com.docbase.knowledge.document.domain.KnowledgeDocumentVersion;
import com.docbase.knowledge.event.OutboxService;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.permission.KnowledgePermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeFolderMapper folderMapper;
    private final KnowledgeDocumentVersionMapper documentVersionMapper;
    private final KnowledgeDocumentAclMapper documentAclMapper;
    private final KnowledgeUploadRequestMapper uploadRequestMapper;
    private final KnowledgePermissionService permissionService;
    private final OutboxService outboxService;

    public KnowledgeDocumentService(KnowledgeDocumentMapper documentMapper,
                                    KnowledgeFolderMapper folderMapper,
                                    KnowledgeDocumentVersionMapper documentVersionMapper,
                                    KnowledgeDocumentAclMapper documentAclMapper,
                                    KnowledgeUploadRequestMapper uploadRequestMapper,
                                    KnowledgePermissionService permissionService,
                                    OutboxService outboxService) {
        this.documentMapper = documentMapper;
        this.folderMapper = folderMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentAclMapper = documentAclMapper;
        this.uploadRequestMapper = uploadRequestMapper;
        this.permissionService = permissionService;
        this.outboxService = outboxService;
    }

    /**
     * Lists documents in a knowledge base with pagination.
     */
    public Page<KnowledgeDocument> listByBaseId(Long knowledgeBaseId, long current, long size, Long userId, boolean isAdmin) {
        permissionService.requireMembership(knowledgeBaseId, userId, isAdmin);

        Page<KnowledgeDocument> page = new Page<>(current, size);
        return documentMapper.selectPage(page,
                new QueryWrapper<KnowledgeDocument>()
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("deleted", 0)
                        .orderByDesc("created_at")
        );
    }

    /**
     * Gets a document by ID. Requires membership in the knowledge base.
     */
    public KnowledgeDocument getById(Long documentId, Long userId, boolean isAdmin) {
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null || doc.getDeleted() == 1) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found");
        }
        permissionService.requireMembership(doc.getKnowledgeBaseId(), userId, isAdmin);
        return doc;
    }

    /**
     * Registers document metadata (without file parsing).
     * Requires EDITOR or higher (or admin:all).
     * Verifies the knowledge base exists first.
     * Validates folderId belongs to the knowledge base.
     */
    @Transactional
    public Long registerDocument(Long knowledgeBaseId, KnowledgeDocument document, Long userId, boolean isAdmin) {
        validateUploadContext(knowledgeBaseId, document.getFolderId(), userId, isAdmin);
        return registerDocumentInternal(knowledgeBaseId, document, null, null, userId);
    }

    /**
     * Registers the metadata, initial version, and outbox event atomically after MinIO upload.
     * The idempotency reservation is marked completed in the same database transaction.
     */
    @Transactional
    public Long registerUploadedDocument(Long knowledgeBaseId, KnowledgeDocument document, Long uploadRequestId,
                                         String leaseToken, Long userId, boolean isAdmin) {
        validateUploadContext(knowledgeBaseId, document.getFolderId(), userId, isAdmin);
        return registerDocumentInternal(knowledgeBaseId, document, uploadRequestId, leaseToken, userId);
    }

    /** Validate all resource-level checks before object storage, and repeat them at registration time. */
    public void validateUploadContext(Long knowledgeBaseId, Long folderId, Long userId, boolean isAdmin) {
        permissionService.requireActiveKnowledgeBase(knowledgeBaseId);
        permissionService.requirePermission(knowledgeBaseId, userId, isAdmin, 3, "Editor permission required");
        if (folderId != null && folderId != 0L) {
            validateFolderBelongsToBase(folderId, knowledgeBaseId);
        }
    }

    private Long registerDocumentInternal(Long knowledgeBaseId, KnowledgeDocument document, Long uploadRequestId,
                                          String leaseToken, Long userId) {

        document.setId(null);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setCreatedBy(userId);
        document.setUpdatedBy(userId);
        document.setVersion(1);
        document.setIngestStatus(1); // PENDING
        document.setStatus(1); // DRAFT
        document.setVisibility(document.getVisibility() != null ? document.getVisibility() : 1);
        document.setDeleted(0);
        documentMapper.insert(document);

        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocumentId(document.getId());
        version.setVersion(document.getVersion());
        version.setOriginalFilename(document.getOriginalFilename());
        version.setObjectKey(document.getObjectKey());
        version.setContentType(document.getContentType());
        version.setFileSize(document.getFileSize());
        version.setChecksum(document.getChecksum());
        version.setIngestStatus(document.getIngestStatus());
        version.setCreatedBy(userId);
        version.setDeleted(0);
        documentVersionMapper.insert(version);

        outboxService.writeEvent(createKnowledgeEvent(
                KnowledgeEvent.DOCUMENT_REGISTERED,
                "document",
                document.getId().toString(),
                knowledgeBaseId,
                document.getId(),
                version.getId(),
                document.getObjectKey(),
                document.getOriginalFilename(),
                document.getContentType(),
                userId
        ));

        if (uploadRequestId != null && uploadRequestMapper.completeIfLeaseOwner(uploadRequestId, leaseToken, document.getId()) != 1) {
            throw new BusinessException("UPLOAD_LEASE_LOST", "Upload lease is no longer owned by this request");
        }

        return document.getId();
    }

    /**
     * Helper to create a KnowledgeEvent with all required fields.
     */
    private KnowledgeEvent createKnowledgeEvent(
            String eventType, String aggregateType, String aggregateId,
            Long knowledgeBaseId, Long documentId, Long versionId, String objectKey,
            String fileName, String contentType, Long userId) {
        return new KnowledgeEvent(
                UUID.randomUUID(),
                eventType,
                aggregateType,
                aggregateId,
                knowledgeBaseId,
                documentId,
                versionId,
                objectKey,
                fileName != null ? fileName : "",
                contentType != null ? contentType : "",
                userId,
                KnowledgeEvent.CURRENT_SCHEMA_VERSION,
                Instant.now(),
                null  // traceId
        );
    }

    /**
     * Updates document metadata. Requires EDITOR or higher (or admin:all).
     * Validates folderId belongs to the document's knowledge base.
     */
    @Transactional
    public void update(Long documentId, KnowledgeDocument updates, Long userId, boolean isAdmin) {
        KnowledgeDocument existing = documentMapper.selectById(documentId);
        if (existing == null || existing.getDeleted() == 1) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found");
        }
        permissionService.requirePermission(existing.getKnowledgeBaseId(), userId, isAdmin, 3, "Editor permission required");

        // Validate folderId belongs to this knowledge base (if specified)
        if (updates.getFolderId() != null && updates.getFolderId() != 0L) {
            validateFolderBelongsToBase(updates.getFolderId(), existing.getKnowledgeBaseId());
        }

        if (updates.getTitle() != null) {
            existing.setTitle(updates.getTitle());
        }
        if (updates.getFolderId() != null) {
            existing.setFolderId(updates.getFolderId());
        }
        if (updates.getVisibility() != null) {
            existing.setVisibility(updates.getVisibility());
        }
        if (updates.getStatus() != null) {
            existing.setStatus(updates.getStatus());
        }
        existing.setUpdatedBy(userId);
        documentMapper.updateById(existing);
    }

    /**
     * Deletes a document. Requires EDITOR or higher (or admin:all).
     * Also soft-deletes associated versions and ACLs.
     */
    @Transactional
    public void delete(Long documentId, Long userId, boolean isAdmin) {
        KnowledgeDocument existing = documentMapper.selectById(documentId);
        if (existing == null || existing.getDeleted() == 1) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found");
        }
        permissionService.requirePermission(existing.getKnowledgeBaseId(), userId, isAdmin, 3, "Editor permission required");

        // Soft delete the document itself
        documentMapper.softDeleteById(documentId);

        // Soft delete associated versions
        documentVersionMapper.softDeleteByDocumentId(documentId);

        // Soft delete associated ACLs
        documentAclMapper.softDeleteByDocumentId(documentId);

        // Write outbox event
        outboxService.writeEvent(createKnowledgeEvent(
                KnowledgeEvent.DOCUMENT_DELETED,
                "document",
                documentId.toString(),
                existing.getKnowledgeBaseId(),
                documentId,
                existing.getVersion() != null ? existing.getVersion().longValue() : null,
                existing.getObjectKey(),
                existing.getOriginalFilename(),
                existing.getContentType(),
                userId
        ));
    }

    /**
     * Computes the set of document IDs the current user is allowed to see in a knowledge base,
     * for the AI chat service to scope RAG retrieval.
     *
     * <p>Only returns documents that are:
     * <ul>
     *   <li>not deleted ({@code deleted = 0})</li>
     *   <li>published ({@code status = 2})</li>
     *   <li>successfully ingested ({@code ingest_status = 3})</li>
     *   <li>visible to the current user per the document's visibility rules</li>
     * </ul>
     *
     * <p>Visibility rules for regular members:
     * <ul>
     *   <li>PUBLIC (3): visible to all members</li>
     *   <li>PRIVATE (1): visible only to the creator or users/grants explicitly granted via ACL</li>
     *   <li>DEPT (2): fail-closed — not visible, because the current JWT carries no reliable
     *       department identity. Only the creator can see their own private/dept documents.</li>
     * </ul>
     *
     * <p>Super-admins ({@code admin:all}) bypass visibility/ACL checks but the knowledge base
     * must still exist and be enabled.
     *
     * <p>The result is capped at {@link KnowledgeDocumentConstants#VISIBLE_DOC_IDS_LIMIT} (1000),
     * which is the maximum the RAG service accepts.
     *
     * @param knowledgeBaseId the knowledge base ID
     * @param userId the current user ID from JWT
     * @param isAdmin whether the current user holds {@code admin:all}
     * @return ordered list of visible document IDs (possibly empty, never null)
     */
    public List<Long> findVisibleDocumentIds(Long knowledgeBaseId, Long userId, boolean isAdmin) {
        // Verify the knowledge base exists and is enabled. Admins cannot bypass existence.
        KnowledgeBase base = permissionService.requireActiveKnowledgeBase(knowledgeBaseId);
        if (base.getStatus() == null || base.getStatus() != 1) {
            throw new BusinessException("KNOWLEDGE_BASE_DISABLED", "Knowledge base is disabled");
        }

        // Regular users must be members of the knowledge base (any role).
        // Admins bypass the membership check but not the existence/enabled checks above.
        if (!isAdmin) {
            permissionService.requireMembership(knowledgeBaseId, userId, false);
        }

        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        wrapper.eq("knowledge_base_id", knowledgeBaseId)
                .eq("deleted", 0)
                .eq("status", KnowledgeDocumentConstants.STATUS_PUBLISHED)
                .eq("ingest_status", KnowledgeDocumentConstants.INGEST_STATUS_SUCCESS);

        if (!isAdmin) {
            // Visibility filter for regular members:
            //   - creator always sees their own documents
            //   - PUBLIC documents are visible to all members
            //   - PRIVATE documents require an explicit user ACL grant
            //   - DEPT documents are fail-closed (no reliable dept identity in JWT)
            wrapper.and(w -> w
                    .eq("created_by", userId)
                    .or().eq("visibility", KnowledgeDocumentConstants.VISIBILITY_PUBLIC)
                    .or().apply("visibility = {0} AND EXISTS ("
                                    + "SELECT 1 FROM knowledge_document_acl acl "
                                    + "WHERE acl.document_id = knowledge_document.id "
                                    + "AND acl.subject_type = {1} "
                                    + "AND acl.subject_id = {2} "
                                    + "AND acl.deleted = {3})",
                            KnowledgeDocumentConstants.VISIBILITY_PRIVATE,
                            KnowledgeDocumentConstants.ACL_SUBJECT_TYPE_USER,
                            userId, 0));
        }

        // Cap at limit + 1 so we can detect truncation without a separate count query.
        wrapper.select("id")
                .orderByAsc("id")
                .last("LIMIT " + (KnowledgeDocumentConstants.VISIBLE_DOC_IDS_LIMIT + 1));

        List<KnowledgeDocument> rows = documentMapper.selectList(wrapper);

        boolean truncated = rows.size() > KnowledgeDocumentConstants.VISIBLE_DOC_IDS_LIMIT;
        if (truncated) {
            log.warn("Visible document ids for knowledge base {} capped at {} (requested for user {})",
                    knowledgeBaseId, KnowledgeDocumentConstants.VISIBLE_DOC_IDS_LIMIT, userId);
            rows = rows.subList(0, KnowledgeDocumentConstants.VISIBLE_DOC_IDS_LIMIT);
        }

        return rows.stream().map(KnowledgeDocument::getId).toList();
    }

    /**
     * Validates that a folder belongs to the specified knowledge base.
     * Prevents cross-knowledge-base IDOR.
     */
    private void validateFolderBelongsToBase(Long folderId, Long knowledgeBaseId) {
        com.docbase.knowledge.folder.domain.KnowledgeFolder folder = folderMapper.selectOne(
                new QueryWrapper<com.docbase.knowledge.folder.domain.KnowledgeFolder>()
                        .eq("id", folderId)
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("deleted", 0)
        );
        if (folder == null) {
            throw new BusinessException("FOLDER_NOT_IN_BASE", "Folder does not belong to this knowledge base");
        }
    }
}
