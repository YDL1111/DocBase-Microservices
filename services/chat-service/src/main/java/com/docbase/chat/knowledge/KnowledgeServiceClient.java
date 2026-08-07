package com.docbase.chat.knowledge;

import com.docbase.common.core.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * Feign client for knowledge-service.
 * Used by chat-service to compute the current user's visible document IDs for a knowledge base.
 *
 * <p>The Authorization header is forwarded so knowledge-service can evaluate visibility
 * against the verified JWT identity. chat-service never computes or trusts these IDs itself.
 *
 * <p>NOTE: knowledge-service returns ApiResponse<List<Long>> (wrapped), not raw List.
 */
@FeignClient(
        name = "knowledge-service",
        contextId = "knowledgeServiceClient",
        path = "/api/knowledge"
)
public interface KnowledgeServiceClient {

    /**
     * @param knowledgeBaseId the knowledge base ID
     * @param authorization   the bearer token to forward (from the incoming request)
     * @param traceId         trace id for log correlation
     * @return ApiResponse wrapping the list of visible document IDs (possibly empty, never null)
     */
    @GetMapping("/bases/{knowledgeBaseId}/visible-document-ids")
    ApiResponse<List<Long>> visibleDocumentIds(@PathVariable("knowledgeBaseId") Long knowledgeBaseId,
                                               @RequestHeader("Authorization") String authorization,
                                               @RequestHeader(value = "X-Trace-Id", required = false) String traceId);
}
