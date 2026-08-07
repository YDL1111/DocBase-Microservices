package com.docbase.chat.session.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.chat.auth.ChatUserPrincipal;
import com.docbase.chat.session.domain.ChatMessage;
import com.docbase.chat.session.domain.ChatSession;
import com.docbase.chat.session.dto.ChatRequestDtos;
import com.docbase.chat.session.service.ChatSessionService;
import com.docbase.common.core.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for AI chat session CRUD.
 * All endpoints require ai:chat:list (or admin:all) and enforce session ownership.
 */
@RestController
@RequestMapping("/api/ai/chat")
public class ChatSessionController {

    private final ChatSessionService sessionService;

    public ChatSessionController(ChatSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('ai:chat:list') or hasAuthority('admin:all')")
    public ApiResponse<Page<ChatSession>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @AuthenticationPrincipal ChatUserPrincipal principal) {
        return ApiResponse.success(sessionService.listSessions(principal.userId(), current, size));
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasAuthority('ai:chat:list') or hasAuthority('admin:all')")
    public ApiResponse<ChatSession> create(
            @RequestBody ChatRequestDtos.CreateSessionRequest request,
            @AuthenticationPrincipal ChatUserPrincipal principal) {
        return ApiResponse.success(
                sessionService.createSession(principal.userId(), request.knowledgeBaseId(), request.title()));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @PreAuthorize("hasAuthority('ai:chat:list') or hasAuthority('admin:all')")
    public ApiResponse<List<ChatMessage>> messages(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal ChatUserPrincipal principal) {
        return ApiResponse.success(sessionService.listMessages(sessionId, principal.userId()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('ai:chat:list') or hasAuthority('admin:all')")
    public ApiResponse<Void> delete(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal ChatUserPrincipal principal) {
        sessionService.deleteSession(sessionId, principal.userId());
        return ApiResponse.success(null);
    }
}
