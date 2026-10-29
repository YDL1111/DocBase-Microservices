package com.docbase.knowledge.search;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RagRetrievalClientTest {
    @Test void sendsInternalCredentialsAndServerScopeAndDecodesResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body = new java.util.concurrent.atomic.AtomicReference<String>();
        var key = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/internal/v1/rag/retrieve", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response = "{\"results\":[{\"document_id\":10,\"content\":\"原文\",\"metadata\":{},\"score\":0.9}],\"count\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var client = new RagRetrievalClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-key");
            var result = client.retrieve(1,"部署",List.of(10L));
            assertEquals("test-key",key.get());assertTrue(body.get().contains("\"visible_document_ids\":[10]"));
            assertEquals(10L,result.getFirst().documentId());assertEquals("原文",result.getFirst().originalContent());
        } finally { server.stop(0); }
    }
    @Test void missingConfigurationIsAnErrorNotEmptyResults() {
        assertThrows(com.docbase.common.core.BusinessException.class, () -> new RagRetrievalClient("http://127.0.0.1:1", "").retrieve(1,"q",List.of(10L)));
    }
    @Test void stripsOnlyVerifiedEmbeddingContext() {
        var hit = new RagRetrievalClient.Candidate(1L,"文档：指南\n章节：安装\n\n实际原文",null,null,null,"安装",Map.of("document_title","指南"));
        assertEquals("实际原文",hit.originalContent());
        var raw = new RagRetrievalClient.Candidate(1L,"文档：其他\n\n实际原文",null,null,null,"安装",Map.of("document_title","指南"));
        assertEquals(raw.content(),raw.originalContent());
    }
}
