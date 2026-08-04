package com.docbase.knowledge.base;

import com.docbase.common.core.BusinessException;
import org.springframework.security.access.AccessDeniedException;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for KnowledgeBaseService using H2 database.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class KnowledgeBaseServiceTest {

    @Configuration
    @Profile("test")
    @MapperScan("com.docbase.knowledge.**.mapper")
    @ComponentScan(basePackages = "com.docbase.knowledge")
    static class TestApplication {
    }

    @Autowired
    KnowledgeBaseService knowledgeBaseService;

    @Autowired
    KnowledgeMemberMapper knowledgeMemberMapper;

    @Autowired
    com.docbase.knowledge.base.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    com.docbase.knowledge.member.service.KnowledgeMemberService memberService;

    @Test
    void createKnowledgeBase_Success() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Test Knowledge Base");
        base.setDescription("A test knowledge base");

        Long id = knowledgeBaseService.create(base, 1L);

        assertThat(id).isNotNull();
        assertThat(id).isGreaterThan(0);

        // Verify owner was added as member
        KnowledgeMember member = knowledgeMemberMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeMember>()
                        .eq("knowledge_base_id", id)
                        .eq("user_id", 1L)
                        .eq("deleted", 0)
        );
        assertThat(member).isNotNull();
        assertThat(member.getMemberRole()).isEqualTo(1); // OWNER
    }

    @Test
    void createKnowledgeBase_PersistsFields() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Field Test Base");
        base.setDescription("Testing field persistence");
        base.setVisibility(2);

        Long id = knowledgeBaseService.create(base, 2L);
        KnowledgeBase saved = knowledgeBaseService.getById(id, 2L, false);

        assertThat(saved.getName()).isEqualTo("Field Test Base");
        assertThat(saved.getDescription()).isEqualTo("Testing field persistence");
        assertThat(saved.getOwnerId()).isEqualTo(2L);
        assertThat(saved.getVisibility()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(1);
    }

    @Test
    void getById_NotMember_ThrowsException() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Private Base");
        Long id = knowledgeBaseService.create(base, 1L);

        // User 2 is not a member, should get AccessDeniedException (403)
        assertThatThrownBy(() -> knowledgeBaseService.getById(id, 2L, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_NonMemberCannotDelete() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Delete Test Base");
        Long id = knowledgeBaseService.create(base, 1L);

        // User 999 (not a member at all) tries to delete - should fail with AccessDeniedException (403)
        assertThatThrownBy(() -> knowledgeBaseService.delete(id, 999L, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_OwnerCanDelete() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Owner Delete Test Base");
        Long id = knowledgeBaseService.create(base, 1L);

        // Owner can delete
        knowledgeBaseService.delete(id, 1L, false);

        // Verify deleted - requireActiveKnowledgeBase should throw
        assertThatThrownBy(() -> knowledgeBaseService.getById(id, 1L, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void delete_AndRecreate_Success() {
        // Create and delete a knowledge base
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Recreatable Base");
        Long id = knowledgeBaseService.create(base, 1L);
        knowledgeBaseService.delete(id, 1L, false);

        // Should be able to create a new base with the same name
        KnowledgeBase newBase = new KnowledgeBase();
        newBase.setName("Recreatable Base");
        Long newId = knowledgeBaseService.create(newBase, 1L);

        assertThat(newId).isNotNull();
        assertThat(newId).isNotEqualTo(id);
    }

    @Test
    void adminAll_CannotAddMemberToNonExistentBase() {
        // admin:all tries to add member to non-existent knowledge base
        assertThatThrownBy(() -> memberService.addMember(99999L, 2L, 3, 1L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void update_AdminCanUpdate() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Update Test Base");
        Long id = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember adminMember = new KnowledgeMember();
        adminMember.setKnowledgeBaseId(id);
        adminMember.setUserId(2L);
        adminMember.setMemberRole(2); // ADMIN
        adminMember.setCreatedBy(1L);
        adminMember.setDeleted(0);
        knowledgeMemberMapper.insert(adminMember);

        // Admin can update
        KnowledgeBase updates = new KnowledgeBase();
        updates.setName("Updated Name");
        knowledgeBaseService.update(id, updates, 2L, false);

        KnowledgeBase saved = knowledgeBaseService.getById(id, 1L, false);
        assertThat(saved.getName()).isEqualTo("Updated Name");
    }

    @Test
    void update_ViewerCannotUpdate() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Viewer Test Base");
        Long id = knowledgeBaseService.create(base, 1L);

        // Add user 2 as viewer
        KnowledgeMember viewerMember = new KnowledgeMember();
        viewerMember.setKnowledgeBaseId(id);
        viewerMember.setUserId(2L);
        viewerMember.setMemberRole(4); // VIEWER
        viewerMember.setCreatedBy(1L);
        viewerMember.setDeleted(0);
        knowledgeMemberMapper.insert(viewerMember);

        // Viewer cannot update - should get AccessDeniedException (403)
        KnowledgeBase updates = new KnowledgeBase();
        updates.setName("Should Fail");
        assertThatThrownBy(() -> knowledgeBaseService.update(id, updates, 2L, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminAll_CanAccessNonMemberResource() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Admin All Test Base");
        Long id = knowledgeBaseService.create(base, 1L);

        // User 999 with admin:all can access even without being a member
        KnowledgeBase result = knowledgeBaseService.getById(id, 999L, true);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(id);
    }
}
