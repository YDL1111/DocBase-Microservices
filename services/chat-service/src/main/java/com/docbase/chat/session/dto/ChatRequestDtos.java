package com.docbase.chat.session.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTOs for chat endpoints.
 */
public final class ChatRequestDtos {

    private ChatRequestDtos() {}

    /** POST /api/ai/chat/sessions */
    public record CreateSessionRequest(
            Long knowledgeBaseId,
            @Size(max = 255) String title
    ) {}

    /** POST /api/ai/chat/stream */
    public record StreamRequest(
            Long sessionId,
            Long knowledgeBaseId,
            @NotNull @Size(min = 1, max = 4000) String question,
            /** Optional client idempotency key to avoid duplicate messages on retry. */
            String clientRequestId
    ) {}

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
