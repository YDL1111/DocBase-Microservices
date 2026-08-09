package com.docbase.knowledge.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.mapper.KnowledgeBaseMapper;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Service for checking knowledge base resource-level permissions.
 *
 * Resource roles:
 * - OWNER (1): Full control, can delete the knowledge base
 * - ADMIN (2): Can manage members, folders, documents
 * - EDITOR (3): Can create/edit folders and documents
 * - VIEWER (4): Can only view
 *
 * Permission hierarchy: OWNER > ADMIN > EDITOR > VIEWER
 *
 * Super-admins (isAdmin=true) bypass resource-level permission checks but
 * still require the knowledge base to exist.
 */
@Service
public class KnowledgePermissionService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeMemberMapper knowledgeMemberMapper;

    public KnowledgePermissionService(KnowledgeBaseMapper knowledgeBaseMapper,
                                      KnowledgeMemberMapper knowledgeMemberMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeMemberMapper = knowledgeMemberMapper;
    }

    /**
     * Checks if the user has at least the required role level in the knowledge base.
     * Super-admins (isAdmin=true) always have permission.
     *
     * @param knowledgeBaseId the knowledge base ID
     * @param userId the user ID from JWT
     * @param isAdmin whether the user has admin:all permission
     * @param requiredRole the minimum role required (1=OWNER, 2=ADMIN, 3=EDITOR, 4=VIEWER)
     * @return true if the user has sufficient permissions
     */
    public boolean hasPermission(Long knowledgeBaseId, Long userId, boolean isAdmin, int requiredRole) {
        if (knowledgeBaseId == null || userId == null) {
            return false;
        }
        // Super-admin bypasses resource-level checks
        if (isAdmin) {
            return true;
        }
        KnowledgeMember member = findActiveMember(knowledgeBaseId, userId);
        if (member == null) {
            return false;
        }
        // Lower number = higher privilege
        return member.getMemberRole() != null && member.getMemberRole() <= requiredRole;
    }

    /**
     * Verifies the user has at least the required role, throwing AccessDeniedException if not.
     * AccessDeniedException results in HTTP 403.
     */
    public void requirePermission(Long knowledgeBaseId, Long userId, boolean isAdmin, int requiredRole, String message) {
        if (!hasPermission(knowledgeBaseId, userId, isAdmin, requiredRole)) {
            throw new AccessDeniedException(message);
        }
    }

    /**
     * Verifies the user belongs to the knowledge base (any role).
     * Super-admins are considered members of all knowledge bases.
     */
    public void requireMembership(Long knowledgeBaseId, Long userId, boolean isAdmin) {
        if (isAdmin) {
            return; // Super-admin is member of all
        }
        if (!canView(knowledgeBaseId, userId, false)) {
            throw new AccessDeniedException("You are not a member of this knowledge base");
        }
    }

    /**
     * Checks if the user can view the knowledge base (any role).
     */
    public boolean canView(Long knowledgeBaseId, Long userId, boolean isAdmin) {
        return hasPermission(knowledgeBaseId, userId, isAdmin, 4);
    }

    /**
     * Finds an active (non-deleted) member record.
     */
    public KnowledgeMember findActiveMember(Long knowledgeBaseId, Long userId) {
        return knowledgeMemberMapper.selectOne(
                new QueryWrapper<KnowledgeMember>()
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("user_id", userId)
                        .eq("deleted", 0)
        );
    }

    /**
     * Verifies the knowledge base exists and is active.
     */
    public KnowledgeBase requireActiveKnowledgeBase(Long knowledgeBaseId) {
        KnowledgeBase base = knowledgeBaseMapper.selectOne(
                new QueryWrapper<KnowledgeBase>()
                        .eq("id", knowledgeBaseId)
                        .eq("deleted", 0)
        );
        if (base == null) {
            throw new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found");
        }
        if (base.getStatus() == null || base.getStatus() != 1) {
            throw new BusinessException("KNOWLEDGE_BASE_DISABLED", "Knowledge base is disabled");
        }
        return base;
    }
}
