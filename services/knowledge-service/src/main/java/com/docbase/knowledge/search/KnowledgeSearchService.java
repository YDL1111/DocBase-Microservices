package com.docbase.knowledge.search;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.document.service.KnowledgeDocumentService;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class KnowledgeSearchService {
    private final KnowledgeDocumentService documents;
    private final RagRetrievalClient rag;
    public KnowledgeSearchService(KnowledgeDocumentService documents, RagRetrievalClient rag) {
        this.documents = documents; this.rag = rag;
    }
    public record Hit(Long documentId, String title, String filename, String content,
                      Integer page, String sheet, Integer slide, String heading) {}
    public List<Hit> search(long baseId, String query, KnowledgeUserPrincipal user) {
        var visible = documents.findSearchableDocumentIds(baseId, user.userId(), user.organizationId(), user.admin());
        if (visible.isEmpty()) return List.of();
        var candidates = rag.retrieve(baseId, query.strip(), visible);
        // Recheck after retrieval so revocations/deletions during a slow embedding call are respected.
        var allowed = new HashSet<>(documents.findSearchableDocumentIds(baseId, user.userId(), user.organizationId(), user.admin()));
        allowed.retainAll(visible);
        List<Hit> hits = new ArrayList<>();
        for (var candidate : candidates) {
            if (candidate.documentId() == null || !allowed.contains(candidate.documentId())
                    || candidate.content() == null || candidate.content().isBlank()) continue;
            var doc = documents.getById(candidate.documentId(), user.userId(), user.organizationId(), user.admin());
            if (!Objects.equals(doc.getKnowledgeBaseId(), baseId)) continue;
            hits.add(new Hit(doc.getId(), doc.getTitle(), doc.getOriginalFilename(), candidate.originalContent(),
                    candidate.page(), candidate.sheet(), candidate.slide(), candidate.heading()));
            if (hits.size() == 10) break;
        }
        return hits;
    }
}
