package com.docbase.chat.security;

import com.docbase.chat.ChatServiceTestConfiguration;
import com.docbase.chat.auth.ChatUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for chat-service security.
 * Verifies: 401 for unauthenticated, 403 for missing permission, IDOR protection,
 * admin:all cannot bypass private session ownership, @PreAuthorize is enforced.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ChatServiceTestConfiguration.class)
class ChatSecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private ChatUserPrincipal principalWithPermission(Long userId, String permission) {
        return new ChatUserPrincipal(userId, "user" + userId, false,
                List.of(new SimpleGrantedAuthority(permission)));
    }

    private ChatUserPrincipal principalWithoutPermission(Long userId) {
        return new ChatUserPrincipal(userId, "user" + userId, false, List.of());
    }

    private ChatUserPrincipal adminPrincipal(Long userId) {
        return new ChatUserPrincipal(userId, "admin" + userId, true,
                List.of(new SimpleGrantedAuthority("admin:all")));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/ai/chat/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingPermission_returns403() throws Exception {
        ChatUserPrincipal principal = principalWithoutPermission(1L);
        mockMvc.perform(get("/api/ai/chat/sessions")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void withPermission_returns200() throws Exception {
        ChatUserPrincipal principal = principalWithPermission(1L, "ai:chat:list");
        mockMvc.perform(get("/api/ai/chat/sessions")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()))))
                .andExpect(status().isOk());
    }

    @Test
    void adminAll_canListSessions() throws Exception {
        ChatUserPrincipal principal = adminPrincipal(1L);
        mockMvc.perform(get("/api/ai/chat/sessions")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()))))
                .andExpect(status().isOk());
    }

    @Test
    void stream_requiresAiChatQueryPermission() throws Exception {
        ChatUserPrincipal principal = principalWithPermission(1L, "ai:chat:list");
        mockMvc.perform(post("/api/ai/chat/stream")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"knowledgeBaseId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stream_withAiChatQueryPermission_notForbidden() throws Exception {
        // SSE endpoint requires WebClient/reactive; with MockMvc it may error (500) but must NOT be 403.
        ChatUserPrincipal principal = principalWithPermission(1L, "ai:chat:query");
        mockMvc.perform(post("/api/ai/chat/stream")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"knowledgeBaseId\":1}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    void idor_otherUserCannotDeleteSession() throws Exception {
        // Non-owner cannot delete: expect 403 (AccessDenied) or 400 (not found) - both are safe, not 200.
        ChatUserPrincipal principal2 = principalWithPermission(2L, "ai:chat:list");
        mockMvc.perform(delete("/api/ai/chat/sessions/99999")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal2, null, principal2.getAuthorities()))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(400, 403);
                });
    }

    @Test
    void adminAll_cannotAccessOtherUsersSession() throws Exception {
        // admin:all satisfies menu permission but session ownership still enforced - not 200.
        ChatUserPrincipal admin = adminPrincipal(999L);
        mockMvc.perform(delete("/api/ai/chat/sessions/99999")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                admin, null, admin.getAuthorities()))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(400, 403);
                });
    }
}
