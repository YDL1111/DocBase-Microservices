package com.docbase.chat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RagChatStreamService} using MockWebServer.
 * Verifies: request shaping, internal API key header, source filtering, error mapping.
 * Does NOT call a real chat provider / BGE-M3 / Chroma.
 */
class RagChatStreamServiceTest {

    private MockWebServer mockWebServer;
    private RagChatStreamService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        objectMapper = new ObjectMapper();
        String baseUrl = mockWebServer.url("/").toString();
        service = new RagChatStreamService(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                objectMapper,
                baseUrl,
                "test-internal-key",
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(120),
                256 * 1024
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void stream_sendsCorrectRequestShape() throws Exception {
        String sse = "data: {\"type\":\"done\",\"data\":null}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sse)
                .setHeader("Content-Type", "text/event-stream"));

        service.stream("问题", 1L, List.of(1L, 2L), 10L).blockLast();

        RecordedRequest request = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request.getHeader("X-Internal-Api-Key")).isEqualTo("test-internal-key");
        assertThat(request.getPath()).isEqualTo("/internal/v1/rag/chat/stream");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"query\":\"问题\"");
        assertThat(body).contains("\"knowledge_base_id\":1");
        assertThat(body).contains("\"visible_document_ids\":[1,2]");
        assertThat(body).contains("\"session_id\":\"10\"");
    }

    @Test
    void stream_emptyVisibleDocIds_sendsEmptyList() throws Exception {
        String sse = "data: {\"type\":\"done\",\"data\":null}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sse)
                .setHeader("Content-Type", "text/event-stream"));

        service.stream("问题", 1L, List.of(), 10L).blockLast();

        RecordedRequest request = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"visible_document_ids\":[]");
    }

    @Test
    void stream_filtersOutOfRangeSourceDocumentIds() {
        String sse = "data: {\"type\":\"sources\",\"data\":[{\"document_id\":1,\"file_name\":\"a.pdf\",\"page\":1},{\"document_id\":999,\"file_name\":\"secret.pdf\",\"page\":1}]}\n\n"
                + "data: {\"type\":\"done\",\"data\":null}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sse)
                .setHeader("Content-Type", "text/event-stream"));

        StepVerifier.create(service.stream("问题", 1L, List.of(1L, 2L), 10L))
                .assertNext(e -> {
                    assertThat(e.event()).isEqualTo(RagDtos.OUT_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<RagDtos.Source> sources = (List<RagDtos.Source>) e.data();
                    assertThat(sources).hasSize(1);
                    assertThat(sources.get(0).document_id()).isEqualTo(1L);
                })
                .assertNext(e -> assertThat(e.event()).isEqualTo(RagDtos.OUT_DONE))
                .verifyComplete();
    }

    @Test
    void stream_ragError_mapsToSafeEvent() {
        // RAG sends {"type":"error","message":"..."} — note: message field, not data
        String sse = "data: {\"type\":\"error\",\"message\":\"internal provider timeout\"}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sse)
                .setHeader("Content-Type", "text/event-stream"));

        StepVerifier.create(service.stream("问题", 1L, List.of(1L), 10L))
                .assertNext(e -> {
                    assertThat(e.event()).isEqualTo(RagDtos.OUT_ERROR);
                    // ErrorPayload directly (NOT wrapped in SseEvent)
                    assertThat(e.data()).isInstanceOf(RagDtos.ErrorPayload.class);
                    RagDtos.ErrorPayload err = (RagDtos.ErrorPayload) e.data();
                    assertThat(err.code()).isEqualTo("RAG_ERROR");
                    // Internal message must NOT be exposed
                    assertThat(err.message()).doesNotContain("provider timeout");
                    assertThat(err.message()).isEqualTo("AI 服务暂时不可用");
                })
                .verifyComplete();
    }

    @Test
    void stream_ragHttpError_returnsUnavailableEvent() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));

        StepVerifier.create(service.stream("问题", 1L, List.of(1L), 10L))
                .assertNext(e -> {
                    assertThat(e.event()).isEqualTo(RagDtos.OUT_ERROR);
                    // ErrorPayload directly (NOT wrapped in SseEvent)
                    assertThat(e.data()).isInstanceOf(RagDtos.ErrorPayload.class);
                    RagDtos.ErrorPayload err = (RagDtos.ErrorPayload) e.data();
                    assertThat(err.code()).isEqualTo("RAG_UNAVAILABLE");
                    assertThat(err.message()).doesNotContain("server error"); // internal detail hidden
                })
                .verifyComplete();
    }

    @Test
    void stream_errorEvent_hasSingleLayerWrapping() {
        // Verify error event data is ErrorPayload directly, not double-wrapped
        String sse = "data: {\"type\":\"error\",\"message\":\"internal failure\"}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sse)
                .setHeader("Content-Type", "text/event-stream"));

        StepVerifier.create(service.stream("问题", 1L, List.of(1L), 10L))
                .assertNext(e -> {
                    assertThat(e.event()).isEqualTo(RagDtos.OUT_ERROR);
                    // Must be ErrorPayload, NOT SseEvent(type=error, data=ErrorPayload)
                    assertThat(e.data()).isInstanceOf(RagDtos.ErrorPayload.class);
                    RagDtos.ErrorPayload err = (RagDtos.ErrorPayload) e.data();
                    assertThat(err.code()).isEqualTo("RAG_ERROR");
                    // Internal message must NOT be exposed
                    assertThat(err.message()).doesNotContain("internal failure");
                    assertThat(err.message()).isEqualTo("AI 服务暂时不可用");
                })
                .verifyComplete();
    }

    @Test
    void stream_doneEmitted() {
        String sse = "data: {\"type\":\"done\",\"data\":null}\n\n";
        mockWebServer.enqueue(new MockResponse()
                .setBody(sse)
                .setHeader("Content-Type", "text/event-stream"));

        StepVerifier.create(service.stream("问题", 1L, List.of(1L), 10L))
                .assertNext(e -> assertThat(e.event()).isEqualTo(RagDtos.OUT_DONE))
                .verifyComplete();
    }
}
