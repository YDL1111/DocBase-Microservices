package com.docbase.knowledge.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.contracts.KnowledgeEvent;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentAclMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentVersionMapper;
import com.docbase.knowledge.event.OutboxService;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.permission.KnowledgePermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeFolderMapper folderMapper;
    private final KnowledgeDocumentVersionMapper documentVersionMapper;
    private final KnowledgeDocumentAclMapper documentAclMapper;
    private final KnowledgePermissionService permissionService;
    private final OutboxService outboxService;

    public KnowledgeDocumentService(KnowledgeDocumentMapper documentMapper,
                                    KnowledgeFolderMapper folderMapper,
                                    KnowledgeDocumentVersionMapper documentVersionMapper,
                                    KnowledgeDocumentAclMapper documentAclMapper,
                                    KnowledgePermissionService permissionService,
                                    OutboxService outboxService) {
        this.documentMapper = documentMapper;
        this.folderMapper = folderMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentAclMapper = documentAclMapper;
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
        permissionService.requireActiveKnowledgeBase(knowledgeBaseId); // Verify base exists
        permissionService.requirePermission(knowledgeBaseId, userId, isAdmin, 3, "Editor permission required");

        // Validate folderId belongs to this knowledge base (if specified)
        if (document.getFolderId() != null && document.getFolderId() != 0L) {
            validateFolderBelongsToBase(document.getFolderId(), knowledgeBaseId);
        }

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

        // Write outbox event
        outboxService.writeEvent(new KnowledgeEvent(
                UUID.randomUUID(),
                KnowledgeEvent.DOCUMENT_REGISTERED,
                "document",
                document.getId().toString(),
                knowledgeBaseId,
                document.getId(),
                document.getObjectKey(),
                userId,
                KnowledgeEvent.CURRENT_SCHEMA_VERSION,
                Instant.now()
        ));

        return document.getId();
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
        outboxService.writeEvent(new KnowledgeEvent(
                UUID.randomUUID(),
                KnowledgeEvent.DOCUMENT_DELETED,
                "document",
                documentId.toString(),
                existing.getKnowledgeBaseId(),
                documentId,
                existing.getObjectKey(),
                userId,
                KnowledgeEvent.CURRENT_SCHEMA_VERSION,
                Instant.now()
        ));
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
