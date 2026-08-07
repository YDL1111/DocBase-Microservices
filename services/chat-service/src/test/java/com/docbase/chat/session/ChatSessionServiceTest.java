package com.docbase.chat.session;

import com.docbase.chat.session.domain.ChatMessage;
import com.docbase.chat.session.domain.ChatSession;
import com.docbase.chat.session.mapper.ChatMessageMapper;
import com.docbase.chat.session.mapper.ChatSessionMapper;
import com.docbase.chat.session.service.ChatSessionService;
import com.docbase.common.core.BusinessException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.chat.ChatServiceTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ChatSessionService}.
 * Covers: ownership checks, IDOR protection, session CRUD, message idempotency.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatServiceTestConfiguration.class)
class ChatSessionServiceTest {

    @Autowired
    ChatSessionService sessionService;

    @Autowired
    ChatSessionMapper sessionMapper;

    @Autowired
    ChatMessageMapper messageMapper;

    private Long user1Session;

    @BeforeEach
    void setUp() {
        // Clean up data from previous tests (H2 in-memory DB persists across tests in same context)
        messageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        sessionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        ChatSession session = sessionService.createSession(1L, 100L, "Test Session");
        user1Session = session.getId();
    }

    @Test
    void createSession_assignsOwnerAndTitle() {
        ChatSession session = sessionService.createSession(5L, 200L, "Hello");
        assertThat(session.getUserId()).isEqualTo(5L);
        assertThat(session.getKnowledgeBaseId()).isEqualTo(200L);
        assertThat(session.getTitle()).isEqualTo("Hello");
        assertThat(session.getStatus()).isEqualTo(ChatConstants.SESSION_STATUS_ACTIVE);
    }

    @Test
    void listSessions_onlyReturnsOwnSessions() {
        sessionService.createSession(2L, 100L, "User2 Session");
        sessionService.createSession(1L, 100L, "User1 Another");

        Page<ChatSession> page = sessionService.listSessions(1L, 1, 20);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getRecords()).allMatch(s -> s.getUserId().equals(1L));
    }

    @Test
    void listSessions_pageSizeCapped() {
        Page<ChatSession> page = sessionService.listSessions(1L, 1, 500);
        // capped at MAX_PAGE_SIZE (100)
        assertThat(page.getSize()).isEqualTo(ChatConstants.MAX_PAGE_SIZE);
    }

    @Test
    void requireOwnedSession_ownerCanAccess() {
        ChatSession session = sessionService.requireOwnedSession(user1Session, 1L);
        assertThat(session.getId()).isEqualTo(user1Session);
    }

    @Test
    void requireOwnedSession_otherUserDenied() {
        assertThatThrownBy(() -> sessionService.requireOwnedSession(user1Session, 2L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void requireOwnedSession_notFound() {
        assertThatThrownBy(() -> sessionService.requireOwnedSession(99999L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void listMessages_ownershipEnforced() {
        assertThatThrownBy(() -> sessionService.listMessages(user1Session, 2L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void deleteSession_ownershipEnforced() {
        assertThatThrownBy(() -> sessionService.deleteSession(user1Session, 2L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void deleteSession_softDeletesSessionAndMessages() {
        ChatMessage msg = sessionService.saveUserMessage(user1Session, 1L, "hi", null);
        sessionService.deleteSession(user1Session, 1L);

        // Query including soft-deleted rows to verify messages were soft-deleted
        List<ChatMessage> messages = messageMapper.selectAllBySessionId(user1Session);
        assertThat(messages).isNotEmpty();
        assertThat(messages).allMatch(m -> m.getDeleted().equals(1));
        // Verify the specific message was soft-deleted
        ChatMessage updatedMsg = messageMapper.selectAllBySessionId(user1Session).stream()
                .filter(m -> m.getId().equals(msg.getId())).findFirst().orElse(null);
        assertThat(updatedMsg).isNotNull();
        assertThat(updatedMsg.getDeleted()).isEqualTo(1);
    }

    @Test
    void saveUserMessage_idempotentByClientRequestId() {
        ChatMessage first = sessionService.saveUserMessage(user1Session, 1L, "question", "req-1");
        ChatMessage second = sessionService.saveUserMessage(user1Session, 1L, "different content", "req-1");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getContent()).isEqualTo("question");
    }

    @Test
    void saveUserMessage_differentClientRequestIdCreatesNew() {
        ChatMessage first = sessionService.saveUserMessage(user1Session, 1L, "q1", "req-1");
        ChatMessage second = sessionService.saveUserMessage(user1Session, 1L, "q2", "req-2");

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void completeAssistantMessage_updatesStatus() {
        ChatMessage placeholder = sessionService.createAssistantPlaceholder(user1Session, 1L);
        assertThat(placeholder.getStatus()).isEqualTo(ChatConstants.MESSAGE_STATUS_STREAMING);

        sessionService.completeAssistantMessage(placeholder.getId(), "answer", "[{}]",
                ChatConstants.MESSAGE_STATUS_COMPLETED, null);

        ChatMessage updated = messageMapper.selectById(placeholder.getId());
        assertThat(updated.getStatus()).isEqualTo(ChatConstants.MESSAGE_STATUS_COMPLETED);
        assertThat(updated.getContent()).isEqualTo("answer");
        assertThat(updated.getSourcesJson()).isEqualTo("[{}]");
        assertThat(updated.getCompletedAt()).isNotNull();
    }
}
