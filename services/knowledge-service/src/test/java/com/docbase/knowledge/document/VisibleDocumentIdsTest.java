package com.docbase.knowledge.document;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.domain.KnowledgeDocumentAcl;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentAclMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.docbase.knowledge.document.service.KnowledgeDocumentService;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link KnowledgeDocumentService#findVisibleDocumentIds}.
 *
 * <p>Covers:
 * <ul>
 *   <li>only published + successfully ingested + non-deleted documents are returned</li>
 *   <li>PUBLIC visibility visible to all members</li>
 *   <li>PRIVATE visibility: creator and ACL-granted users only</li>
 *   <li>DEPT visibility: fail-closed (not visible to other members)</li>
 *   <li>admin:all bypasses visibility/ACL but not base existence/enabled</li>
 *   <li>non-members get no documents</li>
 *   <li>disabled / non-existent knowledge base handling</li>
 *   <li>empty permission (no visible docs) returns empty list</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class VisibleDocumentIdsTest {

    @Autowired
    KnowledgeDocumentService documentService;

    @Autowired
    KnowledgeBaseService knowledgeBaseService;

    @Autowired
    KnowledgeDocumentMapper documentMapper;

    @Autowired
    KnowledgeDocumentAclMapper aclMapper;

    @Autowired
    KnowledgeMemberMapper memberMapper;

    private Long knowledgeBaseId;

    @BeforeEach
    void setUp() {
        KnowledgeBase base = new KnowledgeBase();
        // Unique name per test to satisfy the (name, delete_marker) unique constraint.
        base.setName("ChatVisibleDocs_" + System.nanoTime());
        // creator user 1 becomes owner
        knowledgeBaseId = knowledgeBaseService.create(base, 1L);
    }

    private Long publishDocument(String title, int visibility, Long creator) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setTitle(title);
        doc.setVisibility(visibility);
        doc.setStatus(KnowledgeDocumentConstants.STATUS_PUBLISHED);
        doc.setIngestStatus(KnowledgeDocumentConstants.INGEST_STATUS_SUCCESS);
        doc.setCreatedBy(creator);
        doc.setDeleted(0);
        documentMapper.insert(doc);
        return doc.getId();
    }

    private void softDeleteDocument(Long documentId) {
        documentMapper.softDeleteById(documentId);
    }

    private void grantUserAcl(Long documentId, Long userId) {
        KnowledgeDocumentAcl acl = new KnowledgeDocumentAcl();
        acl.setDocumentId(documentId);
        acl.setKnowledgeBaseId(knowledgeBaseId);
        acl.setSubjectType(KnowledgeDocumentConstants.ACL_SUBJECT_TYPE_USER);
        acl.setSubjectId(userId);
        acl.setPermissionType(KnowledgeDocumentConstants.ACL_PERMISSION_VIEW);
        acl.setCreatedBy(1L);
        acl.setDeleted(0);
        aclMapper.insert(acl);
    }

    private void addMember(Long userId, int role) {
        KnowledgeMember member = new KnowledgeMember();
        member.setKnowledgeBaseId(knowledgeBaseId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setCreatedBy(1L);
        member.setDeleted(0);
        memberMapper.insert(member);
    }

    @Test
    void onlyPublishedAndIngestedAndNonDeletedAreReturned() {
        // user 1 is owner, can see
        Long published = publishDocument("Published", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);
        Long draft = publishDocument("Draft", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);
        updateDoc(draft, KnowledgeDocumentConstants.STATUS_DRAFT, null);
        Long pendingIngest = publishDocument("PendingIngest", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);
        updateDoc(pendingIngest, null, KnowledgeDocumentConstants.INGEST_STATUS_PENDING);
        Long deleted = publishDocument("Deleted", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);
        softDeleteDocument(deleted);

        List<Long> ids = documentService.findVisibleDocumentIds(knowledgeBaseId, 1L, false);

        assertThat(ids).containsExactly(published);
    }

    private void updateDoc(Long id, Integer status, Integer ingestStatus) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (status != null) doc.setStatus(status);
        if (ingestStatus != null) doc.setIngestStatus(ingestStatus);
        documentMapper.updateById(doc);
    }

    @Test
    void publicDocsVisibleToAllMembers() {
        addMember(2L, 4); // viewer
        Long pub = publishDocument("Public", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);

        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 2L, false)).containsExactly(pub);
        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 1L, false)).containsExactly(pub);
    }

    @Test
    void privateDocsVisibleToCreatorAndAclGrantedOnly() {
        Long priv = publishDocument("Private", KnowledgeDocumentConstants.VISIBILITY_PRIVATE, 1L);
        addMember(2L, 4);
        addMember(3L, 4);
        grantUserAcl(priv, 3L);

        // creator sees own private doc
        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 1L, false)).containsExactly(priv);
        // member without ACL grant cannot see
        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 2L, false)).isEmpty();
        // member with ACL grant can see
        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 3L, false)).containsExactly(priv);
    }

    @Test
    void departmentVisibilityIsFailClosed() {
        Long dept = publishDocument("Dept", KnowledgeDocumentConstants.VISIBILITY_DEPT, 1L);
        addMember(2L, 4);

        // creator still sees own doc
        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 1L, false)).containsExactly(dept);
        // other members cannot see dept docs (fail-closed: no reliable dept identity in JWT)
        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 2L, false)).isEmpty();
    }

    @Test
    void adminAllBypassesVisibilityAndAcl() {
        Long priv = publishDocument("Private", KnowledgeDocumentConstants.VISIBILITY_PRIVATE, 1L);
        Long dept = publishDocument("Dept", KnowledgeDocumentConstants.VISIBILITY_DEPT, 1L);
        addMember(2L, 4);

        // admin:all is not a member but bypasses membership + visibility
        List<Long> ids = documentService.findVisibleDocumentIds(knowledgeBaseId, 999L, true);
        assertThat(ids).containsExactlyInAnyOrder(priv, dept);
    }

    @Test
    void adminAllCannotBypassDisabledKnowledgeBase() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("ChatVisibleDocs_Disabled_" + System.nanoTime());
        Long disabledId = knowledgeBaseService.create(base, 1L);
        // disable the base
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(null);
        // update base status via mapper directly
        KnowledgeBase toDisable = new KnowledgeBase();
        toDisable.setId(disabledId);
        toDisable.setStatus(0);
        // use service update path: create a full update
        com.docbase.knowledge.base.domain.KnowledgeBase updates = new com.docbase.knowledge.base.domain.KnowledgeBase();
        updates.setStatus(0);
        knowledgeBaseService.update(disabledId, updates, 1L, true);

        assertThatThrownBy(() -> documentService.findVisibleDocumentIds(disabledId, 999L, true))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("KNOWLEDGE_BASE_DISABLED");
    }

    @Test
    void adminAllCannotBypassNonExistentKnowledgeBase() {
        assertThatThrownBy(() -> documentService.findVisibleDocumentIds(99999L, 999L, true))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("KNOWLEDGE_BASE_NOT_FOUND");
    }

    @Test
    void nonMemberGetsNoDocuments() {
        publishDocument("Public", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);
        // user 2 is not a member
        assertThatThrownBy(() -> documentService.findVisibleDocumentIds(knowledgeBaseId, 2L, false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void emptyPermissionReturnsEmptyList() {
        // only private docs owned by creator; member 2 has no ACL grants
        publishDocument("Private", KnowledgeDocumentConstants.VISIBILITY_PRIVATE, 1L);
        addMember(2L, 4);

        assertThat(documentService.findVisibleDocumentIds(knowledgeBaseId, 2L, false)).isEmpty();
    }

    @Test
    void mixedVisibilityReturnsOnlyVisible() {
        Long pub = publishDocument("Pub", KnowledgeDocumentConstants.VISIBILITY_PUBLIC, 1L);
        Long priv = publishDocument("Priv", KnowledgeDocumentConstants.VISIBILITY_PRIVATE, 1L);
        Long dept = publishDocument("Dept", KnowledgeDocumentConstants.VISIBILITY_DEPT, 1L);
        addMember(2L, 4);
        grantUserAcl(priv, 2L);

        // member 2 sees: public + private(via ACL), not dept(fail-closed), not private-without-ACL
        List<Long> ids = documentService.findVisibleDocumentIds(knowledgeBaseId, 2L, false);
        assertThat(ids).containsExactlyInAnyOrder(pub, priv);
        assertThat(ids).doesNotContain(dept);
    }
}
