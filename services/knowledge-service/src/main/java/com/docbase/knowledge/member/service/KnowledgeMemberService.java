package com.docbase.knowledge.member.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import com.docbase.knowledge.permission.KnowledgePermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class KnowledgeMemberService {

    /**
     * Valid member roles that can be assigned through normal member management.
     * 2 = ADMIN
     * 3 = EDITOR
     * 4 = VIEWER (lowest privilege)
     *
     * NOTE: OWNER (role=1) is excluded. Ownership transfer requires a separate
     * dedicated flow that only the current OWNER can initiate, ensuring the
     * knowledge base always has exactly one OWNER.
     */
    private static final Set<Integer> VALID_MANAGEABLE_ROLES = Set.of(2, 3, 4);

    /**
     * All valid roles including OWNER (used for validation in general context).
     */
    private static final Set<Integer> ALL_VALID_ROLES = Set.of(1, 2, 3, 4);

    private final KnowledgeMemberMapper memberMapper;
    private final KnowledgePermissionService permissionService;

    public KnowledgeMemberService(KnowledgeMemberMapper memberMapper,
                                  KnowledgePermissionService permissionService) {
        this.memberMapper = memberMapper;
        this.permissionService = permissionService;
    }

    /**
     * Lists members of a knowledge base.
     */
    public List<KnowledgeMember> listMembers(Long knowledgeBaseId, Long userId, boolean isAdmin) {
        permissionService.requireMembership(knowledgeBaseId, userId, isAdmin);

        return memberMapper.selectList(
                new QueryWrapper<KnowledgeMember>()
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("deleted", 0)
        );
    }

    /**
     * Adds a member to a knowledge base. Requires ADMIN or higher (or admin:all).
     * Role must be a valid manageable role (2=ADMIN, 3=EDITOR, 4=VIEWER).
     * OWNER role cannot be assigned through this method.
     * Verifies the knowledge base exists first.
     */
    @Transactional
    public void addMember(Long knowledgeBaseId, Long memberUserId, Integer role, Long operatorId, boolean isAdmin) {
        permissionService.requireActiveKnowledgeBase(knowledgeBaseId); // Verify base exists
        permissionService.requirePermission(knowledgeBaseId, operatorId, isAdmin, 2, "Admin permission required");

        // Validate role - only ADMIN, EDITOR, VIEWER can be assigned through member management
        int actualRole = (role != null) ? role : 4;
        if (!VALID_MANAGEABLE_ROLES.contains(actualRole)) {
            throw new BusinessException("INVALID_ROLE", "Role must be 2 (ADMIN), 3 (EDITOR), or 4 (VIEWER)");
        }

        // Check if member already exists
        KnowledgeMember existing = permissionService.findActiveMember(knowledgeBaseId, memberUserId);
        if (existing != null) {
            throw new BusinessException("MEMBER_ALREADY_EXISTS", "User is already a member");
        }

        KnowledgeMember member = new KnowledgeMember();
        member.setKnowledgeBaseId(knowledgeBaseId);
        member.setUserId(memberUserId);
        member.setMemberRole(actualRole);
        member.setCreatedBy(operatorId);
        member.setDeleted(0);
        memberMapper.insert(member);
    }

    /**
     * Updates a member's role. Requires ADMIN or higher (or admin:all).
     * Cannot change OWNER role or promote to OWNER.
     */
    @Transactional
    public void updateMemberRole(Long knowledgeBaseId, Long memberUserId, Integer newRole, Long operatorId, boolean isAdmin) {
        permissionService.requirePermission(knowledgeBaseId, operatorId, isAdmin, 2, "Admin permission required");

        // Validate role - only ADMIN, EDITOR, VIEWER can be assigned
        if (newRole == null || !VALID_MANAGEABLE_ROLES.contains(newRole)) {
            throw new BusinessException("INVALID_ROLE", "Role must be 2 (ADMIN), 3 (EDITOR), or 4 (VIEWER)");
        }

        KnowledgeMember member = permissionService.findActiveMember(knowledgeBaseId, memberUserId);
        if (member == null) {
            throw new BusinessException("MEMBER_NOT_FOUND", "Member not found");
        }

        // Cannot change owner role or promote to owner
        if (member.getMemberRole() == 1) {
            throw new BusinessException("CANNOT_CHANGE_OWNER", "Cannot change owner role");
        }

        member.setMemberRole(newRole);
        memberMapper.updateById(member);
    }

    /**
     * Removes a member from a knowledge base. Requires ADMIN or higher (or admin:all).
     */
    @Transactional
    public void removeMember(Long knowledgeBaseId, Long memberUserId, Long operatorId, boolean isAdmin) {
        permissionService.requirePermission(knowledgeBaseId, operatorId, isAdmin, 2, "Admin permission required");

        KnowledgeMember member = permissionService.findActiveMember(knowledgeBaseId, memberUserId);
        if (member == null) {
            throw new BusinessException("MEMBER_NOT_FOUND", "Member not found");
        }

        // Cannot remove owner
        if (member.getMemberRole() == 1) {
            throw new BusinessException("CANNOT_REMOVE_OWNER", "Cannot remove owner");
        }

        // Use softDeleteById to set both deleted=1 and delete_marker=id
        memberMapper.softDeleteById(member.getId());
    }
}
