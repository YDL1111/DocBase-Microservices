package com.docbase.chat.stream;

import com.docbase.chat.auth.ChatUserPrincipal;
import com.docbase.chat.rag.RagDtos;
import com.docbase.chat.session.dto.ChatRequestDtos;
import com.docbase.common.core.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;

/**
 * SSE streaming endpoint for AI chat.
 *
 * <p>Flow: client -> Gateway -> chat-service -> knowledge-service (visible doc ids)
 * -> chat-service -> rag-service (SSE) -> chat-service forwards SSE -> client.
 *
 * <p>The Gateway route /api/ai/** already has response-timeout: -1 so SSE is not cut off.
 */
@RestController
@RequestMapping("/api/ai/chat")
public class ChatStreamController {

    private final ChatStreamOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public ChatStreamController(ChatStreamOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    @PreAuthorize("hasAuthority('ai:chat:query') or hasAuthority('admin:all')")
    public SseEmitter stream(
            @RequestBody ChatRequestDtos.StreamRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            HttpServletResponse response,
            @AuthenticationPrincipal ChatUserPrincipal principal) {

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        // Send initial comment to establish SSE connection
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        Flux<ServerSentEvent<Object>> flux = orchestrator.stream(
                request.sessionId(),
                request.effectiveKnowledgeBaseIds(),
                request.question(),
                request.clientRequestId(),
                principal.userId(),
                authorization,
                traceId
        );

        Disposable subscription = flux.subscribe(
                event -> {
                    try {
                        // Unified SSE contract: each event is a JSON object with "type" + payload.
                        // Matches docs/api/chat.md: data: {"type":"token","content":"..."}
                        // For error events, event.data() is already an ErrorPayload (not wrapped).
                        String type = event.event() != null ? event.event() : "message";
                        Object payloadData = event.data();
                        // Unwrap: if the data is already an ErrorPayload, use it directly
                        // to avoid double-wrapping {type:error,data:{type:error,data:{...}}}
                        SseEventPayload payload = new SseEventPayload(type, payloadData);
                        String json = objectMapper.writeValueAsString(payload);
                        emitter.send(SseEmitter.event().data(json));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    try {
                        // Single-layer error: {"type":"error","data":{"code":"STREAM_ERROR","message":"..."}}
                        SseEventPayload errPayload = new SseEventPayload("error",
                                new RagDtos.ErrorPayload("STREAM_ERROR", "流传输异常"));
                        String json = objectMapper.writeValueAsString(errPayload);
                        emitter.send(SseEmitter.event().data(json));
                    } catch (IOException ignored) {
                    }
                    emitter.completeWithError(error);
                },
                emitter::complete
        );

        // When the client disconnects, cancel the downstream WebClient subscription
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(e -> subscription.dispose());

        return emitter;
    }

    /**
     * Compatibility endpoint. RAG currently only supports streaming; this returns a clear
     * "not implemented" response rather than faking a synchronous answer.
     */
    @PostMapping("/query")
    @PreAuthorize("hasAuthority('ai:chat:query') or hasAuthority('admin:all')")
    public ApiResponse<Void> query(@RequestBody ChatRequestDtos.QueryRequest request) {
        return ApiResponse.failure("NOT_IMPLEMENTED",
                "当前 RAG 仅支持流式输出，请使用 POST /api/ai/chat/stream");
    }

    /**
     * Unified SSE event payload. Every SSE event sent to the client follows this contract:
     * data: {"type":"<type>","data":<payload>}
     */
    public record SseEventPayload(String type, Object data) {}
}
