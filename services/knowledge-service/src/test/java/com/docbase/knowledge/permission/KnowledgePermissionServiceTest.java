package com.docbase.knowledge.permission;

import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class KnowledgePermissionServiceTest {

    @Autowired
    KnowledgePermissionService permissionService;

    @Autowired
    KnowledgeBaseService knowledgeBaseService;

    @Autowired
    KnowledgeMemberMapper knowledgeMemberMapper;

    @Test
    void owner_HasFullPermissions() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Permission Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        assertThat(permissionService.hasPermission(baseId, 1L, false, 1)).isTrue();
        assertThat(permissionService.hasPermission(baseId, 1L, false, 2)).isTrue();
        assertThat(permissionService.hasPermission(baseId, 1L, false, 3)).isTrue();
        assertThat(permissionService.hasPermission(baseId, 1L, false, 4)).isTrue();
    }

    @Test
    void nonMember_HasNoPermissions() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Non-Member Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        assertThat(permissionService.hasPermission(baseId, 999L, false, 4)).isFalse();
    }

    @Test
    void admin_CanManageButNotOwn() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Admin Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember admin = new KnowledgeMember();
        admin.setKnowledgeBaseId(baseId);
        admin.setUserId(2L);
        admin.setMemberRole(2); // ADMIN
        admin.setCreatedBy(1L);
        admin.setDeleted(0);
        knowledgeMemberMapper.insert(admin);

        assertThat(permissionService.hasPermission(baseId, 2L, false, 1)).isFalse(); // Not owner
        assertThat(permissionService.hasPermission(baseId, 2L, false, 2)).isTrue();  // Admin
        assertThat(permissionService.hasPermission(baseId, 2L, false, 3)).isTrue();  // Editor
    }

    @Test
    void viewer_CanOnlyView() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Viewer Test Base " + System.nanoTime());
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as viewer
        KnowledgeMember viewer = new KnowledgeMember();
        viewer.setKnowledgeBaseId(baseId);
        viewer.setUserId(2L);
        viewer.setMemberRole(4); // VIEWER
        viewer.setCreatedBy(1L);
        viewer.setDeleted(0);
        knowledgeMemberMapper.insert(viewer);

        assertThat(permissionService.hasPermission(baseId, 2L, false, 1)).isFalse();
        assertThat(permissionService.hasPermission(baseId, 2L, false, 2)).isFalse();
        assertThat(permissionService.hasPermission(baseId, 2L, false, 3)).isFalse();
        assertThat(permissionService.hasPermission(baseId, 2L, false, 4)).isTrue();
    }

    @Test
    void adminAll_BypassesResourceChecks() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("AdminAll Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // User 999 with admin:all has all permissions even without being a member
        assertThat(permissionService.hasPermission(baseId, 999L, true, 1)).isTrue();
        assertThat(permissionService.hasPermission(baseId, 999L, true, 4)).isTrue();
    }

    @Test
    void adminAll_CannotBypassResourceExistence() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("AdminAll Existence Test");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Even admin:all cannot operate on non-existent knowledge base
        assertThatThrownBy(() -> permissionService.requireActiveKnowledgeBase(99999L))
                .isInstanceOf(com.docbase.common.core.BusinessException.class);
    }

    @Test
    void requirePermission_ThrowsAccessDeniedWhenDenied() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Require Permission Test");
        Long baseId = knowledgeBaseService.create(base, 1L);

        assertThatThrownBy(() -> permissionService.requirePermission(baseId, 999L, false, 4, "Access denied"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void crossUserAccessDenied() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Cross User Test");
        Long baseId = knowledgeBaseService.create(base, 1L);

        assertThatThrownBy(() -> permissionService.requireMembership(baseId, 2L, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void departmentVisibility_AllowsSameOrganizationAndRejectsDifferentOrganization() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Department Base " + System.nanoTime());
        base.setVisibility(2);
        Long baseId = knowledgeBaseService.create(base, 1L, 100L);

        assertThat(permissionService.canView(baseId, 20L, 100L, false)).isTrue();
        assertThat(permissionService.canView(baseId, 21L, 200L, false)).isFalse();
    }

    @Test
    void departmentVisibility_RemainsFailClosedWithoutOrganizationClaim() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("No Claim Department Base " + System.nanoTime());
        base.setVisibility(2);
        Long baseId = knowledgeBaseService.create(base, 1L, 100L);

        assertThat(permissionService.canView(baseId, 20L, null, false)).isFalse();
    }

    @Test
    void publicVisibility_AllowsNonMemberWithoutOrganization() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Public Base " + System.nanoTime());
        base.setVisibility(3);
        Long baseId = knowledgeBaseService.create(base, 1L, null);

        assertThat(permissionService.canView(baseId, 20L, null, false)).isTrue();
    }
}
