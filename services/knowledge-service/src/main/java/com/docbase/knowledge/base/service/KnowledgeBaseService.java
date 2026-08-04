package com.docbase.knowledge.base.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.contracts.KnowledgeEvent;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.mapper.KnowledgeBaseMapper;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.domain.KnowledgeDocumentAcl;
import com.docbase.knowledge.document.domain.KnowledgeDocumentVersion;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentAclMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentVersionMapper;
import com.docbase.knowledge.event.OutboxService;
import com.docbase.knowledge.folder.domain.KnowledgeFolder;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import com.docbase.knowledge.permission.KnowledgePermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeMemberMapper knowledgeMemberMapper;
    private final KnowledgeFolderMapper folderMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper documentVersionMapper;
    private final KnowledgeDocumentAclMapper documentAclMapper;
    private final KnowledgePermissionService permissionService;
    private final OutboxService outboxService;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeMemberMapper knowledgeMemberMapper,
                                KnowledgeFolderMapper folderMapper,
                                KnowledgeDocumentMapper documentMapper,
                                KnowledgeDocumentVersionMapper documentVersionMapper,
                                KnowledgeDocumentAclMapper documentAclMapper,
                                KnowledgePermissionService permissionService,
                                OutboxService outboxService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeMemberMapper = knowledgeMemberMapper;
        this.folderMapper = folderMapper;
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentAclMapper = documentAclMapper;
        this.permissionService = permissionService;
        this.outboxService = outboxService;
    }

    /**
     * Lists knowledge bases that the user is a member of.
     * Super-admins see all knowledge bases.
     */
    public Page<KnowledgeBase> listForUser(long current, long size, Long userId, boolean isAdmin) {
        Page<KnowledgeBase> page = new Page<>(current, size);

        if (isAdmin) {
            return knowledgeBaseMapper.selectPage(page,
                    new QueryWrapper<KnowledgeBase>()
                            .eq("deleted", 0)
                            .orderByDesc("created_at")
            );
        }

        List<Long> baseIds = knowledgeMemberMapper.selectList(
                new QueryWrapper<KnowledgeMember>()
                        .eq("user_id", userId)
                        .eq("deleted", 0)
        ).stream().map(KnowledgeMember::getKnowledgeBaseId).toList();

        if (baseIds.isEmpty()) {
            return page.setRecords(List.of());
        }

        return knowledgeBaseMapper.selectPage(page,
                new QueryWrapper<KnowledgeBase>()
                        .in("id", baseIds)
                        .eq("deleted", 0)
                        .orderByDesc("created_at")
        );
    }

    /**
     * Gets a knowledge base by ID, verifying the user is a member (or is admin).
     */
    public KnowledgeBase getById(Long id, Long userId, boolean isAdmin) {
        permissionService.requireActiveKnowledgeBase(id);
        permissionService.requireMembership(id, userId, isAdmin);
        return knowledgeBaseMapper.selectById(id);
    }

    /**
     * Creates a new knowledge base. The creator becomes the owner.
     */
    @Transactional
    public Long create(KnowledgeBase base, Long userId) {
        base.setId(null);
        base.setOwnerId(userId);
        base.setCreatedBy(userId);
        base.setUpdatedBy(userId);
        base.setStatus(1);
        base.setVisibility(base.getVisibility() != null ? base.getVisibility() : 1);
        base.setSortNum(base.getSortNum() != null ? base.getSortNum() : 0);
        base.setDeleted(0);
        knowledgeBaseMapper.insert(base);

        // Add creator as owner
        KnowledgeMember member = new KnowledgeMember();
        member.setKnowledgeBaseId(base.getId());
        member.setUserId(userId);
        member.setMemberRole(1); // OWNER
        member.setCreatedBy(userId);
        member.setDeleted(0);
        knowledgeMemberMapper.insert(member);

        // Write outbox event
        outboxService.writeEvent(new KnowledgeEvent(
                UUID.randomUUID(),
                KnowledgeEvent.BASE_CREATED,
                "knowledge_base",
                base.getId().toString(),
                base.getId(),
                null,
                null,
                userId,
                KnowledgeEvent.CURRENT_SCHEMA_VERSION,
                Instant.now()
        ));

        return base.getId();
    }

    /**
     * Updates a knowledge base. Requires ADMIN or higher (or admin:all).
     */
    @Transactional
    public void update(Long id, KnowledgeBase updates, Long userId, boolean isAdmin) {
        permissionService.requireActiveKnowledgeBase(id);
        permissionService.requirePermission(id, userId, isAdmin, 2, "Admin permission required");

        KnowledgeBase existing = knowledgeBaseMapper.selectById(id);
        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
        if (updates.getVisibility() != null) {
            existing.setVisibility(updates.getVisibility());
        }
        if (updates.getStatus() != null) {
            existing.setStatus(updates.getStatus());
        }
        if (updates.getSortNum() != null) {
            existing.setSortNum(updates.getSortNum());
        }
        existing.setUpdatedBy(userId);
        knowledgeBaseMapper.updateById(existing);
    }

    /**
     * Deletes a knowledge base. Requires OWNER role (or admin:all).
     * Cascade soft-deletes members, folders, and documents.
     */
    @Transactional
    public void delete(Long id, Long userId, boolean isAdmin) {
        permissionService.requireActiveKnowledgeBase(id);
        permissionService.requirePermission(id, userId, isAdmin, 1, "Only owner can delete knowledge base");

        // Cascade soft delete to all associated data
        softDeleteKnowledgeBaseCascade(id, userId);

        // Write outbox event
        outboxService.writeEvent(new KnowledgeEvent(
                UUID.randomUUID(),
                KnowledgeEvent.BASE_DELETED,
                "knowledge_base",
                id.toString(),
                id,
                null,
                null,
                userId,
                KnowledgeEvent.CURRENT_SCHEMA_VERSION,
                Instant.now()
        ));
    }

    /**
     * Cascade soft delete: marks all associated data as deleted.
     * Handles: members, folders, documents, document versions, and ACLs.
     *
     * IMPORTANT: Each record must have both deleted=1 AND delete_marker=id set,
     * otherwise the unique constraint will block recreation of records with the same name.
     */
    private void softDeleteKnowledgeBaseCascade(Long knowledgeBaseId, Long userId) {
        // Soft delete the knowledge base itself
        knowledgeBaseMapper.softDelete(knowledgeBaseId, userId);

        // Soft delete members - use raw SQL to set delete_marker = id
        knowledgeMemberMapper.softDeleteByBaseId(knowledgeBaseId);

        // Soft delete folders
        folderMapper.softDeleteByBaseId(knowledgeBaseId);

        // Soft delete documents
        documentMapper.softDeleteByBaseId(knowledgeBaseId);

        // Soft delete document versions (by document IDs in this base)
        documentVersionMapper.softDeleteByBaseId(knowledgeBaseId);

        // Soft delete document ACLs
        documentAclMapper.softDeleteByBaseId(knowledgeBaseId);
    }
}
