package com.docbase.chat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calls the rag-service internal SSE endpoint and re-emits normalized SSE events.
 *
 * <p>The connection uses WebClient (the only WebFlux dependency in chat-service, which remains a
 * servlet/MVC application). The rag-service address is configurable via {@code docbase.rag.base-url}.
 *
 * <p>Internal RAG exceptions are never propagated raw to the client — they are mapped to a
 * safe {@link RagStreamException} with a generic error code.
 */
@Service
public class RagChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(RagChatStreamService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String internalApiKey;

    public RagChatStreamService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${docbase.rag.base-url:http://rag-service:8090}") String baseUrl,
            @Value("${RAG_INTERNAL_API_KEY:}") String internalApiKey,
            @Value("${docbase.rag.connect-timeout:5s}") Duration connectTimeout,
            @Value("${docbase.rag.idle-timeout:120s}") Duration idleTimeout,
            @Value("${docbase.rag.max-in-memory-size:256KB}") int maxInMemorySize) {
        this.objectMapper = objectMapper;
        this.internalApiKey = internalApiKey;
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySize))
                .build();
    }

    /**
     * Streams RAG chat events for the given query, scoped to the visible document IDs.
     *
     * @param query                the user question
     * @param knowledgeBaseId      the knowledge base id
     * @param visibleDocumentIds   document ids the user is allowed to see (may be empty, never null)
     * @param sessionId            chat session id (optional, passed through)
     * @return flux of normalized outbound SSE events
     */
    public Flux<ServerSentEvent<Object>> stream(
            String query,
            Long knowledgeBaseId,
            List<Long> visibleDocumentIds,
            Long sessionId) {

        RagDtos.ChatRequest request = new RagDtos.ChatRequest(
                query,
                knowledgeBaseId,
                visibleDocumentIds != null ? visibleDocumentIds : List.of(),
                sessionId != null ? String.valueOf(sessionId) : null
        );

        AtomicBoolean sourcesValidated = new AtomicBoolean(false);

        return webClient.post()
                .uri("/internal/v1/rag/chat/stream")
                .header("X-Internal-Api-Key", internalApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(120))
                .flatMapIterable(RagChatStreamService::splitSseEvents)
                .filter(line -> line != null && !line.isBlank())
                .flatMap(line -> parseAndValidate(line, visibleDocumentIds))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.warn("RAG service HTTP error: status={}", e.getStatusCode());
                    return Flux.just(errorEvent("RAG_UNAVAILABLE", "AI 服务暂时不可用"));
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("RAG service timeout");
                    return Flux.just(errorEvent("RAG_TIMEOUT", "AI 服务响应超时"));
                })
                .onErrorResume(Exception.class, e -> {
                    if (e instanceof RagStreamException) {
                        return Flux.just(errorEvent(((RagStreamException) e).getErrorCode(), e.getMessage()));
                    }
                    log.warn("RAG stream error: {}", e.getMessage());
                    return Flux.just(errorEvent("RAG_UNAVAILABLE", "AI 服务暂时不可用"));
                });
    }

    /**
     * Splits a raw SSE stream into individual event payloads (the part after "data: ").
     * SSE events are separated by blank lines; each event may have one or more "data:" lines.
     */
    static List<String> splitSseEvents(String raw) {
        List<String> events = new ArrayList<>();
        if (raw == null) {
            return events;
        }
        StringBuilder current = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (current.length() > 0) {
                    events.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            String value = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(value);
        }
        if (current.length() > 0) {
            events.add(current.toString());
        }
        return events;
    }

    private Flux<ServerSentEvent<Object>> parseAndValidate(String line, List<Long> visibleDocumentIds) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : null;
            if (type == null) {
                return Flux.empty();
            }
            JsonNode data = node.get("data");
            switch (type) {
                case RagDtos.EVT_TOKEN -> {
                    // RAG sends {"type":"token","content":"..."}
                    String token = node.has("content") ? node.get("content").asText() : "";
                    return Flux.just(ServerSentEvent.<Object>builder().event(RagDtos.OUT_TOKEN).data(token).build());
                }
                case RagDtos.EVT_METADATA -> {
                    // RAG sends {"type":"metadata","sources":[...]}
                    JsonNode sourcesNode = node.has("sources") ? node.get("sources") : data;
                    return Flux.just(ServerSentEvent.<Object>builder().event(RagDtos.OUT_SOURCES)
                            .data(filterSources(sourcesNode, visibleDocumentIds))
                            .build());
                }
                case RagDtos.EVT_SOURCES -> {
                    // RAG sends {"type":"sources","data":[...]}
                    JsonNode sourcesNode = data != null ? data : node.get("sources");
                    return Flux.just(ServerSentEvent.<Object>builder().event(RagDtos.OUT_SOURCES)
                            .data(filterSources(sourcesNode, visibleDocumentIds))
                            .build());
                }
                case RagDtos.EVT_DONE -> {
                    return Flux.just(ServerSentEvent.<Object>builder().event(RagDtos.OUT_DONE).data(null).build());
                }
                case RagDtos.EVT_ERROR -> {
                    // RAG sends {"type":"error","message":"..."}
                    String msg = node.has("message") ? node.get("message").asText()
                            : (data != null ? data.asText() : "AI 服务暂时不可用");
                    // Never propagate RAG internal error text to the client.
                    log.warn("RAG returned error event: {}", msg);
                    return Flux.just(errorEvent("RAG_ERROR", "AI 服务暂时不可用"));
                }
                default -> {
                    return Flux.empty();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse RAG SSE line: {}", e.getMessage());
            return Flux.empty();
        }
    }

    /**
     * Filters RAG source entries so only document ids in the visible set are forwarded.
     * Any source with an out-of-range document id is dropped and a security warning is logged.
     */
    private Object filterSources(JsonNode data, List<Long> visibleDocumentIds) {
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<RagDtos.Source> filtered = new ArrayList<>();
        for (JsonNode item : data) {
            long docId = item.has("document_id") ? item.get("document_id").asLong() : -1;
            if (visibleDocumentIds.contains(docId)) {
                filtered.add(new RagDtos.Source(
                        docId,
                        item.has("file_name") ? item.get("file_name").asText() : null,
                        item.has("page") && !item.get("page").isNull() ? item.get("page").asInt() : null
                ));
            } else if (docId != -1) {
                log.warn("RAG returned out-of-range source document_id={}; dropped for security", docId);
            }
        }
        return filtered;
    }

    private ServerSentEvent<Object> errorEvent(String code, String message) {
        // Use ErrorPayload directly (NOT wrapped in SseEvent) to avoid double-wrapping.
        // Final output: {"type":"error","data":{"code":"...","message":"..."}}
        return ServerSentEvent.<Object>builder().event(RagDtos.OUT_ERROR)
                .data(new RagDtos.ErrorPayload(code, message))
                .build();
    }
}
