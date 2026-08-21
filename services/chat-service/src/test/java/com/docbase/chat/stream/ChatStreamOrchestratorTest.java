package com.docbase.chat.stream;

import com.docbase.chat.knowledge.KnowledgeServiceClient;
import com.docbase.chat.rag.RagChatStreamService;
import com.docbase.chat.rag.RagDtos;
import com.docbase.chat.session.domain.ChatSession;
import com.docbase.chat.session.service.ChatSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docbase.common.core.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatStreamOrchestratorTest {

    @Mock ChatSessionService sessionService;
    @Mock KnowledgeServiceClient knowledgeClient;
    @Mock RagChatStreamService ragStreamService;
    @Mock StreamConcurrencyLock concurrencyLock;

    private ChatStreamOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ChatStreamOrchestrator(sessionService, knowledgeClient,
                ragStreamService, concurrencyLock, new ObjectMapper());
    }

    @Test
    void newGeneralChat_allowsNullKnowledgeBaseAndSkipsKnowledgeService() {
        ChatSession session = session(11L, null);
        when(sessionService.createSession(7L, List.of(), "hello")).thenReturn(session);
        stubSuccessfulStream(11L);

        StepVerifier.create(orchestrator.stream(null, List.of(), "hello", "request-1", 7L, "Bearer x", "trace"))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.event()).isEqualTo(RagDtos.OUT_SESSION))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.event()).isEqualTo(RagDtos.OUT_DONE))
                .verifyComplete();

        verify(knowledgeClient, never()).visibleDocumentIds(any(), any(), any());
        verify(ragStreamService).stream(eq("hello"), eq(List.of()), eq(11L), eq(List.of()));
    }

    @Test
    void existingGeneralChat_usesStoredNullKnowledgeBase() {
        ChatSession session = session(12L, null);
        when(sessionService.requireOwnedSession(12L, 7L)).thenReturn(session);
        stubSuccessfulStream(12L);

        StepVerifier.create(orchestrator.stream(12L, List.of(), "hello", "request-2", 7L, "Bearer x", "trace"))
                .expectNextCount(2)
                .verifyComplete();

        verify(knowledgeClient, never()).visibleDocumentIds(any(), any(), any());
        verify(ragStreamService).stream(eq("hello"), eq(List.of()), eq(12L), eq(List.of()));
    }

    @Test
    void existingMultiKnowledgeChat_fetchesEachVisibilityScope() {
        ChatSession session = session(13L, null);
        session.setKnowledgeBaseIds(List.of(10L, 20L));
        when(sessionService.requireOwnedSession(13L, 7L)).thenReturn(session);
        when(sessionService.prepareStream(13L, 7L, "hello", "request-3"))
                .thenReturn(new ChatSessionService.StreamPrepareResult(false, 22L));
        when(knowledgeClient.visibleDocumentIds(10L, "Bearer x", "trace"))
                .thenReturn(ApiResponse.success(List.of(101L)));
        when(knowledgeClient.visibleDocumentIds(20L, "Bearer x", "trace"))
                .thenReturn(ApiResponse.success(List.of(201L, 202L)));
        when(concurrencyLock.tryAcquire(7L)).thenReturn("token");
        when(ragStreamService.stream(eq("hello"),
                org.mockito.ArgumentMatchers.<RagDtos.KnowledgeScope>anyList(), eq(13L),
                org.mockito.ArgumentMatchers.<RagDtos.HistoryMessage>anyList()))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().event(RagDtos.OUT_DONE).data(null).build()));

        StepVerifier.create(orchestrator.stream(13L, List.of(10L, 20L), "hello", "request-3", 7L, "Bearer x", "trace"))
                .expectNextCount(2)
                .verifyComplete();

        verify(ragStreamService).stream(eq("hello"), eq(List.of(
                new RagDtos.KnowledgeScope(10L, List.of(101L)),
                new RagDtos.KnowledgeScope(20L, List.of(201L, 202L)))), eq(13L), eq(List.of()));
    }

    @Test
    void boundKnowledgeBasesWithNoSearchableDocumentsReturnExplicitErrorWithoutPersistingOrCallingRag() {
        ChatSession session = session(14L, 10L);
        when(sessionService.requireOwnedSession(14L, 7L)).thenReturn(session);
        when(knowledgeClient.visibleDocumentIds(10L, "Bearer x", "trace"))
                .thenReturn(ApiResponse.success(List.of()));

        StepVerifier.create(orchestrator.stream(14L, List.of(10L), "hello", "request-4", 7L, "Bearer x", "trace"))
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.event()).isEqualTo(RagDtos.OUT_ERROR);
                    org.assertj.core.api.Assertions.assertThat((RagDtos.ErrorPayload) event.data())
                            .extracting(RagDtos.ErrorPayload::code)
                            .isEqualTo("NO_SEARCHABLE_DOCUMENTS");
                })
                .verifyComplete();

        verify(sessionService, never()).prepareStream(any(), any(), any(), any());
        verify(ragStreamService, never()).stream(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<RagDtos.KnowledgeScope>anyList(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.<RagDtos.HistoryMessage>anyList());
    }

    @Test
    void knowledgeScopeFailureReturnsExplicitErrorWithoutPersistingOrCallingRag() {
        ChatSession session = session(15L, 10L);
        when(sessionService.requireOwnedSession(15L, 7L)).thenReturn(session);
        when(knowledgeClient.visibleDocumentIds(10L, "Bearer x", "trace"))
                .thenThrow(new RuntimeException("knowledge unavailable"));

        StepVerifier.create(orchestrator.stream(15L, List.of(10L), "hello", "request-5", 7L, "Bearer x", "trace"))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat((RagDtos.ErrorPayload) event.data())
                        .extracting(RagDtos.ErrorPayload::code)
                        .isEqualTo("KNOWLEDGE_SCOPE_UNAVAILABLE"))
                .verifyComplete();

        verify(sessionService, never()).prepareStream(any(), any(), any(), any());
        verify(ragStreamService, never()).stream(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<RagDtos.KnowledgeScope>anyList(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.<RagDtos.HistoryMessage>anyList());
    }

    @Test
    void multiKnowledgeChatSkipsEmptyScopesAndSearchesNonEmptyScopes() {
        ChatSession session = session(16L, null);
        session.setKnowledgeBaseIds(List.of(10L, 20L));
        when(sessionService.requireOwnedSession(16L, 7L)).thenReturn(session);
        when(knowledgeClient.visibleDocumentIds(10L, "Bearer x", "trace"))
                .thenReturn(ApiResponse.success(List.of()));
        when(knowledgeClient.visibleDocumentIds(20L, "Bearer x", "trace"))
                .thenReturn(ApiResponse.success(List.of(201L)));
        when(sessionService.prepareStream(16L, 7L, "hello", "request-6"))
                .thenReturn(new ChatSessionService.StreamPrepareResult(false, 23L));
        when(concurrencyLock.tryAcquire(7L)).thenReturn("token");
        when(ragStreamService.stream(eq("hello"), eq(List.of(
                new RagDtos.KnowledgeScope(20L, List.of(201L)))), eq(16L), eq(List.of())))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().event(RagDtos.OUT_DONE).data(null).build()));

        StepVerifier.create(orchestrator.stream(16L, List.of(10L, 20L), "hello", "request-6", 7L, "Bearer x", "trace"))
                .expectNextCount(2)
                .verifyComplete();

        verify(ragStreamService).stream(eq("hello"), eq(List.of(
                new RagDtos.KnowledgeScope(20L, List.of(201L)))), eq(16L), eq(List.of()));
    }

    private void stubSuccessfulStream(Long sessionId) {
        when(sessionService.prepareStream(sessionId, 7L, "hello", "request-" + (sessionId == 11L ? "1" : "2")))
                .thenReturn(new ChatSessionService.StreamPrepareResult(false, 21L));
        when(concurrencyLock.tryAcquire(7L)).thenReturn("token");
        when(ragStreamService.stream(eq("hello"), eq(List.of()), eq(sessionId), eq(List.of())))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder()
                        .event(RagDtos.OUT_DONE).data(null).build()));
    }

    private ChatSession session(Long id, Long knowledgeBaseId) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(7L);
        session.setKnowledgeBaseId(knowledgeBaseId);
        session.setKnowledgeBaseIds(knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId));
        return session;
    }
}
