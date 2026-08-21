package com.docbase.chat.session.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request DTOs for chat endpoints.
 */
public final class ChatRequestDtos {

    private ChatRequestDtos() {}

    /** POST /api/ai/chat/sessions */
    public record CreateSessionRequest(
            Long knowledgeBaseId,
            @Size(max = 20) List<Long> knowledgeBaseIds,
            @Size(max = 255) String title
    ) {
        public List<Long> effectiveKnowledgeBaseIds() {
            return knowledgeBaseIds != null ? knowledgeBaseIds
                    : knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId);
        }
    }

    /** PUT /api/ai/chat/sessions/{sessionId}/knowledge-bases */
    public record ReplaceKnowledgeBasesRequest(
            @Size(max = 20) List<Long> knowledgeBaseIds
    ) {
        public List<Long> effectiveKnowledgeBaseIds() {
            return knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        }
    }

    /** POST /api/ai/chat/stream */
    public record StreamRequest(
            Long sessionId,
            Long knowledgeBaseId,
            @Size(max = 20) List<Long> knowledgeBaseIds,
            @NotNull @Size(min = 1, max = 4000) String question,
            /** Optional client idempotency key to avoid duplicate messages on retry. */
            String clientRequestId
    ) {
        public List<Long> effectiveKnowledgeBaseIds() {
            return knowledgeBaseIds != null ? knowledgeBaseIds
                    : knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId);
        }
    }

    /** POST /api/ai/chat/query (compatibility — not yet implemented for streaming-only RAG). */
    public record QueryRequest(
            Long sessionId,
            Long knowledgeBaseId,
            @NotNull @Size(min = 1, max = 4000) String question
    ) {}

    /** GET /api/ai/chat/sessions query parameters. */
    public record SessionQuery(
            long current,
            long size
    ) {}
}
