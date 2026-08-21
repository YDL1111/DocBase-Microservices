package com.docbase.chat.stream;

import com.docbase.chat.knowledge.KnowledgeServiceClient;
import com.docbase.chat.rag.RagChatStreamService;
import com.docbase.common.core.ApiResponse;
import com.docbase.chat.rag.RagDtos;
import com.docbase.chat.rag.RagStreamException;
import com.docbase.chat.session.ChatConstants;
import com.docbase.chat.session.domain.ChatMessage;
import com.docbase.chat.session.domain.ChatSession;
import com.docbase.chat.session.service.ChatSessionService;
import com.docbase.chat.session.service.ChatSessionService.StreamPrepareResult;
import com.docbase.common.core.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the full streaming flow:
 * <ol>
 *   <li>Validate input and ownership</li>
 *   <li>Resolve or create the chat session</li>
 *   <li>Call knowledge-service (Feign) to compute visible document IDs for the current user</li>
 *   <li>In a short transaction, persist the USER message and an ASSISTANT/STREAMING placeholder</li>
 *   <li>Commit the transaction, then connect to RAG (WebClient SSE) — no DB transaction is held
 *       during the network call</li>
 *   <li>Stream tokens to the client; accumulate assistant content (bounded)</li>
 *   <li>On done, persist the final ASSISTANT message (COMPLETED) with sources</li>
 *   <li>On error/timeout/cancel, persist FAILED/CANCELLED and emit a safe error event</li>
 * </ol>
 *
 * <p>The Redis per-user concurrency lock is acquired before the RAG call and released on every
 * terminal path (success, error, timeout, client disconnect).
 */
@Service
public class ChatStreamOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamOrchestrator.class);

    private final ChatSessionService sessionService;
    private final KnowledgeServiceClient knowledgeClient;
    private final RagChatStreamService ragStreamService;
    private final StreamConcurrencyLock concurrencyLock;
    private final ObjectMapper objectMapper;

    public ChatStreamOrchestrator(ChatSessionService sessionService,
                                  KnowledgeServiceClient knowledgeClient,
                                  RagChatStreamService ragStreamService,
                                  StreamConcurrencyLock concurrencyLock,
                                  ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.knowledgeClient = knowledgeClient;
        this.ragStreamService = ragStreamService;
        this.concurrencyLock = concurrencyLock;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the streaming flow and returns the SSE flux.
     *
     * @param sessionId        existing session id (nullable — a new session is created if absent)
     * @param knowledgeBaseIds optional knowledge base ids; empty creates a general chat session
     * @param question         the user question
     * @param clientRequestId  optional idempotency key
     * @param userId           current user id from JWT (never from the client)
     * @param authorization    bearer token to forward to knowledge-service
     * @param traceId          trace id to forward
     */
    public Flux<ServerSentEvent<Object>> stream(
            Long sessionId,
            List<Long> knowledgeBaseIds,
            String question,
            String clientRequestId,
            Long userId,
            String authorization,
            String traceId) {

        // 1. Validate
        if (question == null || question.isBlank()) {
            return Flux.just(RagDtos.errorEvent("INVALID_INPUT", "问题不能为空"));
        }
        if (question.length() > ChatConstants.MAX_QUESTION_LENGTH) {
            return Flux.just(RagDtos.errorEvent("QUESTION_TOO_LONG", "问题过长"));
        }

        // 2. Resolve / create session (short transaction).
        // For existing sessions, ALWAYS use the session's stored knowledgeBaseId — never trust
        // the request's knowledgeBaseId, which could be null or a different KB.
        final ChatSession session;
        final List<Long> effectiveKnowledgeBaseIds;
        try {
            if (sessionId != null) {
                session = sessionService.requireOwnedSession(sessionId, userId);
                effectiveKnowledgeBaseIds = session.getKnowledgeBaseIds();
                // Existing session bindings are authoritative. A non-empty conflicting request is rejected.
                if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()
                        && !knowledgeBaseIds.equals(effectiveKnowledgeBaseIds)) {
                    return Flux.just(RagDtos.errorEvent("KB_MISMATCH", "会话知识库与请求不一致"));
                }
            } else {
                effectiveKnowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
                session = sessionService.createSession(userId, effectiveKnowledgeBaseIds,
                        question.length() > 20 ? question.substring(0, 20) + "..." : question);
            }
        } catch (BusinessException e) {
            return Flux.just(RagDtos.errorEvent(e.code(), e.getMessage()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return Flux.just(RagDtos.errorEvent("FORBIDDEN", "无权访问该会话"));
        }

        final Long sid = session.getId();

        // 3. Compute visible document IDs from knowledge-service (uses current JWT identity).
        final List<RagDtos.KnowledgeScope> knowledgeScopes;
        try {
            knowledgeScopes = effectiveKnowledgeBaseIds.stream()
                    .map(knowledgeBaseId -> new RagDtos.KnowledgeScope(
                            knowledgeBaseId, fetchVisibleDocIds(knowledgeBaseId, authorization, traceId)))
                    .filter(scope -> !scope.visible_document_ids().isEmpty())
                    .toList();
        } catch (KnowledgeScopeException exception) {
            return Flux.just(RagDtos.errorEvent(exception.code, exception.getMessage()));
        }
        if (!effectiveKnowledgeBaseIds.isEmpty() && knowledgeScopes.isEmpty()) {
            return Flux.just(RagDtos.errorEvent("NO_SEARCHABLE_DOCUMENTS",
                    "已绑定知识库中没有已发布且入库成功的可见文档，请先发布文档或等待入库完成"));
        }

        // 4. Transactional prepare: atomically persist USER message + ASSISTANT placeholder.
        // If clientRequestId duplicates an existing user message, do NOT create a new assistant
        // placeholder and do NOT call RAG again — return a duplicate marker event.
        final StreamPrepareResult prepareResult;
        try {
            prepareResult = sessionService.prepareStream(sid, userId, question, clientRequestId);
        } catch (Exception e) {
            log.warn("Failed to prepare stream for session {}: {}", sid, e.getMessage());
            return Flux.just(RagDtos.errorEvent("INTERNAL_ERROR", "消息持久化失败"));
        }

        if (prepareResult.isDuplicate()) {
            // Duplicate clientRequestId: do not call RAG again.
            log.debug("Duplicate stream request for session {} clientRequestId={}; skipping RAG", sid, clientRequestId);
            return Flux.just(RagDtos.errorEvent("DUPLICATE_REQUEST", "该请求正在处理中，请勿重复提交"));
        }

        final Long assistantMessageId = prepareResult.assistantMessageId();

        // 5. Emit session/message metadata, then stream from RAG.
        // session event payload: {"sessionId":1,"messageId":2}
        Flux<ServerSentEvent<Object>> header = Flux.just(
                ServerSentEvent.<Object>builder().event(RagDtos.OUT_SESSION)
                        .data(new SessionEventPayload(sid, assistantMessageId))
                        .build()
        );

        // 6. Acquire concurrency lock, connect to RAG, persist result, release lock — all terminal
        //    paths release the lock.
        Flux<ServerSentEvent<Object>> body = streamWithLock(sid, assistantMessageId, userId,
                question, knowledgeScopes);

        return header.concatWith(body);
    }

    /** Backward-compatible adapter for clients/tests using the former single-KB contract. */
    public Flux<ServerSentEvent<Object>> stream(
            Long sessionId, Long knowledgeBaseId, String question, String clientRequestId,
            Long userId, String authorization, String traceId) {
        return stream(sessionId, knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId),
                question, clientRequestId, userId, authorization, traceId);
    }

    private Flux<ServerSentEvent<Object>> streamWithLock(
            Long sessionId, Long assistantMessageId, Long userId,
            String question, List<RagDtos.KnowledgeScope> knowledgeScopes) {

        String token = concurrencyLock.tryAcquire(userId);
        if (token == null) {
            // Persist failed assistant message
            sessionService.completeAssistantMessage(assistantMessageId, null, null,
                    ChatConstants.MESSAGE_STATUS_FAILED, "CONCURRENT_STREAM_LIMIT");
            return Flux.just(RagDtos.errorEvent("CONCURRENT_STREAM_LIMIT", "已有正在进行中的对话，请稍后再试"));
        }

        final StringBuilder answerBuffer = new StringBuilder();
        final AtomicReference<List<RagDtos.Source>> finalSources = new AtomicReference<>(List.of());
        // Track explicit terminal events from RAG (not Flux completion).
        final AtomicBoolean doneReceived = new AtomicBoolean(false);
        final AtomicBoolean errorReceived = new AtomicBoolean(false);

        Flux<ServerSentEvent<Object>> ragFlux = knowledgeScopes.size() <= 1
                ? ragStreamService.stream(
                        question,
                        knowledgeScopes.isEmpty() ? null : knowledgeScopes.get(0).knowledge_base_id(),
                        knowledgeScopes.isEmpty() ? List.of() : knowledgeScopes.get(0).visible_document_ids(),
                        sessionId)
                : ragStreamService.stream(question, knowledgeScopes, sessionId);
        return ragFlux
                .doOnNext(event -> {
                    String evt = event.event();
                    Object data = event.data();
                    if (RagDtos.OUT_TOKEN.equals(evt) && data instanceof String s) {
                        appendBounded(answerBuffer, s);
                    } else if (RagDtos.OUT_SOURCES.equals(evt) && data instanceof List<?> list) {
                        List<RagDtos.Source> srcs = new ArrayList<>();
                        for (Object o : list) {
                            if (o instanceof RagDtos.Source src) {
                                srcs.add(src);
                            }
                        }
                        finalSources.set(srcs);
                    } else if (RagDtos.OUT_DONE.equals(evt)) {
                        doneReceived.set(true);
                    } else if (RagDtos.OUT_ERROR.equals(evt)) {
                        errorReceived.set(true);
                    }
                })
                .doOnError(e -> {
                    // Network/unexpected error: persist FAILED
                    sessionService.completeAssistantMessage(assistantMessageId, answerBuffer.toString(),
                            null, ChatConstants.MESSAGE_STATUS_FAILED, "RAG_ERROR");
                })
                .doFinally(signal -> {
                    try {
                        if (signal == SignalType.CANCEL) {
                            // Client disconnected: mark CANCELLED
                            sessionService.completeAssistantMessage(assistantMessageId, answerBuffer.toString(),
                                    null, ChatConstants.MESSAGE_STATUS_CANCELLED, "CLIENT_CANCELLED");
                        } else if (errorReceived.get()) {
                            // RAG sent an explicit error event: mark FAILED
                            sessionService.completeAssistantMessage(assistantMessageId, answerBuffer.toString(),
                                    null, ChatConstants.MESSAGE_STATUS_FAILED, "RAG_ERROR");
                        } else if (doneReceived.get()) {
                            // Explicit done: mark COMPLETED
                            String sourcesJson = null;
                            try {
                                sourcesJson = objectMapper.writeValueAsString(finalSources.get());
                            } catch (JsonProcessingException ignored) {
                            }
                            sessionService.completeAssistantMessage(assistantMessageId, answerBuffer.toString(),
                                    sourcesJson, ChatConstants.MESSAGE_STATUS_COMPLETED, null);
                        } else if (signal == SignalType.ON_COMPLETE) {
                            // Stream ended without done/error (premature EOF): mark FAILED
                            sessionService.completeAssistantMessage(assistantMessageId, answerBuffer.toString(),
                                    null, ChatConstants.MESSAGE_STATUS_FAILED, "RAG_INCOMPLETE");
                        }
                    } finally {
                        concurrencyLock.release(userId, token);
                    }
                });
    }

    private List<Long> fetchVisibleDocIds(Long knowledgeBaseId, String authorization, String traceId) {
        try {
            ApiResponse<List<Long>> response = knowledgeClient.visibleDocumentIds(knowledgeBaseId, authorization, traceId);
            if (response != null && response.success() && response.data() != null) {
                return response.data();
            }
            log.warn("Knowledge visible-document-ids returned non-success for kb={}: {}",
                    knowledgeBaseId, response != null ? response.code() : "null");
            String responseCode = response != null ? response.code() : null;
            if ("FORBIDDEN".equals(responseCode) || "UNAUTHENTICATED".equals(responseCode)) {
                throw new KnowledgeScopeException("KNOWLEDGE_SCOPE_FORBIDDEN", "无权检索所绑定的知识库");
            }
            throw new KnowledgeScopeException("KNOWLEDGE_SCOPE_UNAVAILABLE", "知识库检索范围暂时不可用，请稍后重试");
        } catch (Exception e) {
            if (e instanceof KnowledgeScopeException scopeException) throw scopeException;
            if (e instanceof FeignException feignException
                    && (feignException.status() == 401 || feignException.status() == 403)) {
                log.warn("Knowledge visibility denied for kb={}: HTTP {}", knowledgeBaseId, feignException.status());
                throw new KnowledgeScopeException("KNOWLEDGE_SCOPE_FORBIDDEN", "无权检索所绑定的知识库");
            }
            log.warn("Failed to fetch visible document ids for kb={}: {}", knowledgeBaseId, e.getMessage());
            throw new KnowledgeScopeException("KNOWLEDGE_SCOPE_UNAVAILABLE", "知识库检索范围暂时不可用，请稍后重试");
        }
    }

    private static final class KnowledgeScopeException extends RuntimeException {
        private final String code;

        private KnowledgeScopeException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void appendBounded(StringBuilder buffer, String token) {
        if (buffer.length() + token.length() > ChatConstants.MAX_RESPONSE_LENGTH) {
            return;
        }
        buffer.append(token);
    }

    /**
     * Session event payload sent at the start of a stream: {"sessionId":1,"messageId":2}
     */
    public record SessionEventPayload(Long sessionId, Long messageId) {}
}
