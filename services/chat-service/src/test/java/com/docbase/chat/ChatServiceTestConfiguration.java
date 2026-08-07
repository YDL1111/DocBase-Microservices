package com.docbase.chat;

import com.docbase.chat.knowledge.KnowledgeServiceClient;
import com.docbase.common.core.ApiResponse;
import com.docbase.common.security.AuthVersionChecker;
import com.docbase.common.security.JwtVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Test configuration that provides mock beans for JWT verification and knowledge-service client.
 */
@TestConfiguration
public class ChatServiceTestConfiguration {

    static List<Long> visibleDocIds = List.of();

    @Bean
    @Primary
    public JwtVerifier testJwtVerifier() {
        return Mockito.mock(JwtVerifier.class);
    }

    @Bean
    @Primary
    public AuthVersionChecker testAuthVersionChecker() {
        AuthVersionChecker checker = Mockito.mock(AuthVersionChecker.class);
        Mockito.when(checker.isAuthVersionValid(Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(true);
        return checker;
    }


    /**
     * Provides a mock KnowledgeServiceClient for tests. The Feign client is disabled in tests
     * (docbase.chat.feign.enabled=false) so this mock is used instead.
     */
    @Bean
    public KnowledgeServiceClient knowledgeServiceClient() {
        return (knowledgeBaseId, authorization, traceId) -> ApiResponse.success(visibleDocIds);
    }
}
