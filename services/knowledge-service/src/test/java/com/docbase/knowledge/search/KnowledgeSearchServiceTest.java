package com.docbase.knowledge.search;
import com.docbase.knowledge.document.service.KnowledgeDocumentService;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeSearchServiceTest {
    final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
    final RagRetrievalClient rag = mock(RagRetrievalClient.class);
    final KnowledgeSearchService service = new KnowledgeSearchService(documents, rag);
    final KnowledgeUserPrincipal user = new KnowledgeUserPrincipal(7L, "tester", 2L, false, List.of());
    @Test void emptyScopeNeverCallsRag() {
        when(documents.findSearchableDocumentIds(1L,7L,2L,false)).thenReturn(List.of());
        assertTrue(service.search(1,"部署",user).isEmpty()); verifyNoInteractions(rag);
    }
    @Test void deniesBeforeRetrieval() {
        when(documents.findSearchableDocumentIds(1L,7L,2L,false)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.search(1,"部署",user));
        verifyNoInteractions(rag);
    }
    @Test void scopesRetrievalAndDropsForeignAndRevokedDocuments() {
        when(documents.findSearchableDocumentIds(1L,7L,2L,false)).thenReturn(List.of(10L,11L),List.of(10L));
        when(rag.retrieve(1,"部署",List.of(10L,11L))).thenReturn(List.of(
            new RagRetrievalClient.Candidate(999L,"secret",null,null,null,null,null),
            new RagRetrievalClient.Candidate(11L,"revoked",null,null,null,null,null),
            new RagRetrievalClient.Candidate(10L,"original passage",3,null,null,"安装",null)));
        var doc = new KnowledgeDocument(); doc.setId(10L);doc.setKnowledgeBaseId(1L);doc.setTitle("Server title");doc.setOriginalFilename("guide.pdf");
        when(documents.getById(10L,7L,2L,false)).thenReturn(doc);
        var hits = service.search(1," 部署 ",user);
        assertEquals(1,hits.size());assertEquals("Server title",hits.getFirst().title());
        assertEquals("original passage",hits.getFirst().content());assertEquals(3,hits.getFirst().page());
        verify(documents,never()).getById(eq(999L),any(),any(),anyBoolean());
        verify(documents,never()).getById(eq(11L),any(),any(),anyBoolean());
    }
}
