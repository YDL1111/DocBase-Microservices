package com.docbase.knowledge.member;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import com.docbase.knowledge.member.service.KnowledgeMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class KnowledgeMemberServiceTest {

    @Autowired
    KnowledgeMemberService memberService;

    @Autowired
    KnowledgeBaseService knowledgeBaseService;

    @Autowired
    KnowledgeMemberMapper knowledgeMemberMapper;

    @Test
    void addMember_RoleOwner_Rejected() {
        // Create knowledge base (user 1 is owner)
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Owner Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember adminMember = new KnowledgeMember();
        adminMember.setKnowledgeBaseId(baseId);
        adminMember.setUserId(2L);
        adminMember.setMemberRole(2); // ADMIN
        adminMember.setCreatedBy(1L);
        adminMember.setDeleted(0);
        knowledgeMemberMapper.insert(adminMember);

        // ADMIN tries to add user 3 as OWNER (role=1) - should be rejected
        assertThatThrownBy(() -> memberService.addMember(baseId, 3L, 1, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Role must be");
    }

    @Test
    void updateMemberRole_ToOwner_Rejected() {
        // Create knowledge base (user 1 is owner)
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Update Owner Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember adminMember = new KnowledgeMember();
        adminMember.setKnowledgeBaseId(baseId);
        adminMember.setUserId(2L);
        adminMember.setMemberRole(2); // ADMIN
        adminMember.setCreatedBy(1L);
        adminMember.setDeleted(0);
        knowledgeMemberMapper.insert(adminMember);

        // Add user 3 as editor
        KnowledgeMember editorMember = new KnowledgeMember();
        editorMember.setKnowledgeBaseId(baseId);
        editorMember.setUserId(3L);
        editorMember.setMemberRole(3); // EDITOR
        editorMember.setCreatedBy(1L);
        editorMember.setDeleted(0);
        knowledgeMemberMapper.insert(editorMember);

        // ADMIN tries to promote user 3 to OWNER (role=1) - should be rejected
        assertThatThrownBy(() -> memberService.updateMemberRole(baseId, 3L, 1, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Role must be");
    }

    @Test
    void addMember_ValidRoles_Accepted() {
        // Create knowledge base (user 1 is owner)
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Valid Roles Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember adminMember = new KnowledgeMember();
        adminMember.setKnowledgeBaseId(baseId);
        adminMember.setUserId(2L);
        adminMember.setMemberRole(2); // ADMIN
        adminMember.setCreatedBy(1L);
        adminMember.setDeleted(0);
        knowledgeMemberMapper.insert(adminMember);

        // ADMIN adds user 3 as EDITOR (role=3) - should succeed
        memberService.addMember(baseId, 3L, 3, 2L, false);

        KnowledgeMember added = knowledgeMemberMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeMember>()
                        .eq("knowledge_base_id", baseId)
                        .eq("user_id", 3L)
                        .eq("deleted", 0)
        );
        assertThat(added).isNotNull();
        assertThat(added.getMemberRole()).isEqualTo(3);
    }

    @Test
    void removeMember_AndReadd_Success() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Readd Member Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember adminMember = new KnowledgeMember();
        adminMember.setKnowledgeBaseId(baseId);
        adminMember.setUserId(2L);
        adminMember.setMemberRole(2); // ADMIN
        adminMember.setCreatedBy(1L);
        adminMember.setDeleted(0);
        knowledgeMemberMapper.insert(adminMember);

        // Add user 3 as editor
        memberService.addMember(baseId, 3L, 3, 2L, false);

        // Remove user 3
        memberService.removeMember(baseId, 3L, 2L, false);

        // Re-add user 3 - should succeed (proves delete_marker was set correctly)
        // If delete_marker wasn't set, this would fail with unique constraint violation
        memberService.addMember(baseId, 3L, 4, 2L, false);

        KnowledgeMember readded = knowledgeMemberMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeMember>()
                        .eq("knowledge_base_id", baseId)
                        .eq("user_id", 3L)
                        .eq("deleted", 0)
        );
        assertThat(readded).isNotNull();
        assertThat(readded.getMemberRole()).isEqualTo(4);
    }

    @Test
    void addMember_InvalidRole_Rejected() {
        // Create knowledge base (user 1 is owner)
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Invalid Role Test Base");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Add user 2 as admin
        KnowledgeMember adminMember = new KnowledgeMember();
        adminMember.setKnowledgeBaseId(baseId);
        adminMember.setUserId(2L);
        adminMember.setMemberRole(2); // ADMIN
        adminMember.setCreatedBy(1L);
        adminMember.setDeleted(0);
        knowledgeMemberMapper.insert(adminMember);

        // ADMIN tries to add user 3 with invalid role (role=0) - should be rejected
        assertThatThrownBy(() -> memberService.addMember(baseId, 3L, 0, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Role must be");

        // ADMIN tries to add user 3 with invalid role (role=5) - should be rejected
        assertThatThrownBy(() -> memberService.addMember(baseId, 3L, 5, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Role must be");

        // ADMIN tries to add user 3 with invalid role (role=-1) - should be rejected
        assertThatThrownBy(() -> memberService.addMember(baseId, 3L, -1, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Role must be");
    }
}
