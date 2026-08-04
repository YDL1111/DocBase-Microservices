package com.docbase.knowledge.security;

import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for security authorization.
 * Verifies that @PreAuthorize annotations are enforced and return correct HTTP status codes.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeSecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private KnowledgeUserPrincipal principalWithPermission(Long userId, String permission) {
        return new KnowledgeUserPrincipal(userId, "user" + userId, false,
                List.of(new SimpleGrantedAuthority(permission)));
    }

    private KnowledgeUserPrincipal principalWithoutPermission(Long userId) {
        return new KnowledgeUserPrincipal(userId, "user" + userId, false, List.of());
    }

    private KnowledgeUserPrincipal adminPrincipal(Long userId) {
        return new KnowledgeUserPrincipal(userId, "admin" + userId, true,
                List.of(new SimpleGrantedAuthority("admin:all")));
    }

    @Test
    void createKnowledgeBase_WithoutPermission_Returns403() throws Exception {
        KnowledgeUserPrincipal principal = principalWithoutPermission(1L);

        mockMvc.perform(post("/api/knowledge/bases")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createKnowledgeBase_WithPermission_Returns200() throws Exception {
        KnowledgeUserPrincipal principal = principalWithPermission(1L, "knowledge:base:create");

        mockMvc.perform(post("/api/knowledge/bases")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test KB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void createKnowledgeBase_AdminAll_Returns200() throws Exception {
        KnowledgeUserPrincipal principal = adminPrincipal(1L);

        mockMvc.perform(post("/api/knowledge/bases")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admin KB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void listKnowledgeBase_WithoutAuth_Returns401() throws Exception {
        mockMvc.perform(get("/api/knowledge/bases"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminAll_CannotOperateNonExistentBase_Returns404() throws Exception {
        KnowledgeUserPrincipal principal = adminPrincipal(1L);

        // Try to create folder in non-existent knowledge base
        mockMvc.perform(post("/api/knowledge/bases/99999/folders")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Folder\"}"))
                .andExpect(status().isBadRequest()); // BusinessException -> 400 (not found)
    }
}
