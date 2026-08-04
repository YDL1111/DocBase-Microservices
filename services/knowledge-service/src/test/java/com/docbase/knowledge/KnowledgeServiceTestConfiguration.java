package com.docbase.knowledge;

import com.docbase.common.security.AuthVersionChecker;
import com.docbase.common.security.JwtVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that provides mock beans for JWT verification.
 * Tests should use @SpringBootTest with custom properties to control JWT behavior.
 */
@TestConfiguration
public class KnowledgeServiceTestConfiguration {

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

    @Bean
    @Primary
    public ObjectMapper testObjectMapper() {
        return new ObjectMapper();
    }
}
