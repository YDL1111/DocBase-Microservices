package com.docbase.chat.rag;

import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

/**
 * DTOs for the RAG internal API (snake_case JSON).
 * Mirrors rag-service app/schemas/events.py ChatRequest and SSE events.
 */
public final class RagDtos {

    private RagDtos() {}

    /** Request body for POST /internal/v1/rag/chat/stream */
    public record ChatRequest(
            String query,
            List<KnowledgeScope> knowledge_scopes,
            Long knowledge_base_id,
            List<Long> visible_document_ids,
            String session_id
    ) {}

    public record KnowledgeScope(
            Long knowledge_base_id,
            List<Long> visible_document_ids
    ) {}

    /** A normalized SSE event emitted by chat-service to the client. */
    public record SseEvent(String type, Object data) {
        /** Convenience: error event with code + message. */
        public static SseEvent error(String code, String message) {
            return new SseEvent(OUT_ERROR, new ErrorPayload(code, message));
        }
    }

    /** Error payload sent to the client. */
    public record ErrorPayload(String code, String message) {}

    /** RAG internal SSE event as received from rag-service. */
    public record RagSseEvent(String type, Object data) {}

    /** Source entry returned by RAG. */
    public record Source(Long document_id, String file_name, Integer page) {}

    // Event type constants (from rag-service)
    public static final String EVT_METADATA = "metadata";
    public static final String EVT_TOKEN = "token";
    public static final String EVT_SOURCES = "sources";
    public static final String EVT_DONE = "done";
    public static final String EVT_ERROR = "error";

    // Outbound event types (chat-service to client)
    public static final String OUT_SESSION = "session";
    public static final String OUT_TOKEN = "token";
    public static final String OUT_SOURCES = "sources";
    public static final String OUT_DONE = "done";
    public static final String OUT_ERROR = "error";

    /**
     * Builds a terminal error SSE event.
     * The data is an ErrorPayload (NOT wrapped in SseEvent) to avoid double-wrapping.
     * Final output: {"type":"error","data":{"code":"...","message":"..."}}
     */
    public static ServerSentEvent<Object> errorEvent(String code, String message) {
        return ServerSentEvent.<Object>builder().event(OUT_ERROR)
                .data(new ErrorPayload(code, message))
                .build();
    }
}
