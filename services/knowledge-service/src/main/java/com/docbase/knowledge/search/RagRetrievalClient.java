package com.docbase.knowledge.search;

import com.docbase.common.core.BusinessException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Component
public class RagRetrievalClient {
    private final RestClient client;
    private final String key;
    public RagRetrievalClient(@Value("${RAG_BASE_URL:http://rag-service:8090}") String url,
                              @Value("${RAG_INTERNAL_API_KEY:}") String key) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.key = key;
    }
    public record Candidate(@JsonProperty("document_id") Long documentId, String content,
                            Integer page, String sheet, Integer slide,
                            @JsonProperty("heading_path") String heading, Map<String, Object> metadata) {
        public String originalContent() {
            String title = metadata == null ? "" : String.valueOf(metadata.getOrDefault("document_title", "")).strip();
            String section = heading == null ? "" : heading.strip();
            var prefixes = new java.util.ArrayList<String>();
            if (!title.isEmpty()) prefixes.add("文档：" + title);
            if (!section.isEmpty() && !section.equals(title)) prefixes.add("章节：" + section);
            String prefix = String.join("\n", prefixes) + "\n\n";
            return !prefixes.isEmpty() && content != null && content.startsWith(prefix)
                    ? content.substring(prefix.length()) : content;
        }
    }
    public record Response(List<Candidate> results) {}
    public List<Candidate> retrieve(long baseId, String query, List<Long> visible) {
        if (key.isBlank()) throw unavailable();
        try {
            var response = client.post().uri("/internal/v1/rag/retrieve")
                    .header("X-Internal-Api-Key", key)
                    .body(Map.of("query", query, "knowledge_base_id", baseId,
                            "visible_document_ids", visible, "top_k", 10))
                    .retrieve().body(Response.class);
            if (response == null || response.results() == null) throw unavailable();
            return response.results();
        } catch (Exception ex) {
            throw unavailable();
        }
    }
    private BusinessException unavailable() {
        return new BusinessException("SEARCH_UNAVAILABLE", "知识检索暂时不可用，请稍后重试");
    }
}
