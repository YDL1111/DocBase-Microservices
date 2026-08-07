package com.docbase.chat.session.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.chat.session.ChatConstants;
import com.docbase.chat.session.domain.ChatMessage;
import com.docbase.chat.session.domain.ChatSession;
import com.docbase.chat.session.mapper.ChatMessageMapper;
import com.docbase.chat.session.mapper.ChatSessionMapper;
import com.docbase.common.core.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Service for AI chat sessions and messages.
 *
 * <p>Ownership rule: every read/write/delete of a session or its messages is authorized by
 * {@code sessionId + currentUserId}. Ordinary users can only access their own sessions.
 * admin:all satisfies menu/permission checks but does NOT bypass private session ownership
 * (no admin view of other users' chats in this phase).
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    public ChatSessionService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * Lists sessions owned by the current user (paginated, newest first).
     */
    public Page<ChatSession> listSessions(Long userId, long current, long size) {
        long cappedSize = Math.min(size, ChatConstants.MAX_PAGE_SIZE);
        Page<ChatSession> page = new Page<>(current, cappedSize);
        return sessionMapper.selectPage(page, new QueryWrapper<ChatSession>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .orderByDesc("updated_at"));
    }

    /**
     * Creates a new session owned by the current user.
     */
    @Transactional
    public ChatSession createSession(Long userId, Long knowledgeBaseId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setKnowledgeBaseId(knowledgeBaseId);
        session.setTitle(truncate(title, ChatConstants.MAX_TITLE_LENGTH));
        session.setStatus(ChatConstants.SESSION_STATUS_ACTIVE);
        session.setDeleted(0);
        sessionMapper.insert(session);
        return session;
    }

    /**
     * Finds a session by id, verifying it is owned by the current user.
     * Throws AccessDeniedException if the session belongs to another user.
     * Throws BusinessException if not found / soft-deleted.
     */
    public ChatSession requireOwnedSession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getDeleted() == 1) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            // Same response shape as not-found to avoid leaking session existence.
            throw new AccessDeniedException("You do not have permission to access this session");
        }
        return session;
    }

    /**
     * Lists messages of a session (ascending by creation time), after ownership check.
     */
    public List<ChatMessage> listMessages(Long sessionId, Long userId) {
        requireOwnedSession(sessionId, userId);
        return messageMapper.selectList(new QueryWrapper<ChatMessage>()
                .eq("session_id", sessionId)
                .eq("deleted", 0)
                .orderByAsc("created_at"));
    }

    /**
     * Soft-deletes a session and its messages in one transaction, after ownership check.
     */
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = requireOwnedSession(sessionId, userId);
        session.setDeleted(1);
        sessionMapper.updateById(session);
        // Soft-delete messages via direct SQL to avoid MyBatis-Plus logic-delete quirks on H2
        messageMapper.softDeleteBySessionId(sessionId);
        log.info("Deleted session {} for user {}", sessionId, userId);
    }

    /**
     * Saves a USER message. Uses clientRequestId for idempotency: if a message with the same
     * clientRequestId already exists in the session, returns the existing one instead of creating
     * a duplicate.
     *
     * @return the saved-or-existing user message
     */
    public ChatMessage saveUserMessage(Long sessionId, Long userId, String content, String clientRequestId) {
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            ChatMessage existing = messageMapper.selectActiveByClientRequestId(
                    sessionId, clientRequestId, ChatConstants.MESSAGE_ROLE_USER);
            if (existing != null) {
                log.debug("Duplicate user message for clientRequestId={}; returning existing", clientRequestId);
                return existing;
            }
        }
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setUserId(userId);
        msg.setRole(ChatConstants.MESSAGE_ROLE_USER);
        msg.setContent(content);
        msg.setStatus(ChatConstants.MESSAGE_STATUS_COMPLETED);
        msg.setClientRequestId(clientRequestId);
        msg.setDeleted(0);
        messageMapper.insert(msg);
        return msg;
    }

    /**
     * Inserts a placeholder ASSISTANT message in STREAMING status before the RAG call.
     */
    @Transactional
    public ChatMessage createAssistantPlaceholder(Long sessionId, Long userId) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setUserId(userId);
        msg.setRole(ChatConstants.MESSAGE_ROLE_ASSISTANT);
        msg.setContent("");
        msg.setStatus(ChatConstants.MESSAGE_STATUS_STREAMING);
        msg.setDeleted(0);
        messageMapper.insert(msg);
        return msg;
    }

    /**
     * Updates the assistant message after the stream completes (success or failure).
     * Must be called in its own short transaction, after the RAG SSE call has finished.
     */
    @Transactional
    public void completeAssistantMessage(Long messageId, String content, String sourcesJson,
                                         int status, String errorCode) {
        ChatMessage msg = messageMapper.selectById(messageId);
        if (msg == null || msg.getDeleted() == 1) {
            return;
        }
        msg.setContent(content);
        msg.setSourcesJson(sourcesJson);
        msg.setStatus(status);
        msg.setErrorCode(errorCode);
        msg.setCompletedAt(LocalDateTime.now());
        messageMapper.updateById(msg);
    }

    /**
     * Result of preparing a stream: whether it is a duplicate request, and the assistant message id.
     */
    public record StreamPrepareResult(boolean isDuplicate, Long assistantMessageId) {}

    /**
     * Atomically prepares the stream by persisting the USER message and ASSISTANT placeholder.
     * If clientRequestId duplicates an existing active user message, returns isDuplicate=true
     * without creating any new messages (so RAG is never called twice).
     */
    @Transactional
    public StreamPrepareResult prepareStream(Long sessionId, Long userId, String content, String clientRequestId) {
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            ChatMessage existing = messageMapper.selectActiveByClientRequestId(
                    sessionId, clientRequestId, ChatConstants.MESSAGE_ROLE_USER);
            if (existing != null) {
                log.debug("Duplicate user message for clientRequestId={}; returning existing", clientRequestId);
                return new StreamPrepareResult(true, null);
            }
        }
        // Insert USER message
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setUserId(userId);
        userMsg.setRole(ChatConstants.MESSAGE_ROLE_USER);
        userMsg.setContent(content);
        userMsg.setStatus(ChatConstants.MESSAGE_STATUS_COMPLETED);
        userMsg.setClientRequestId(clientRequestId);
        userMsg.setDeleted(0);
        messageMapper.insert(userMsg);

        // Insert ASSISTANT placeholder
        ChatMessage assistantMsg = createAssistantPlaceholder(sessionId, userId);
        return new StreamPrepareResult(false, assistantMsg.getId());
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
