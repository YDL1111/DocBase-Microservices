package com.docbase.knowledge.member.controller;

import com.docbase.common.core.ApiResponse;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.service.KnowledgeMemberService;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/bases/{knowledgeBaseId}/members")
public class KnowledgeMemberController {

    private final KnowledgeMemberService memberService;

    public KnowledgeMemberController(KnowledgeMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('knowledge:member:list') or hasAuthority('admin:all')")
    public ApiResponse<List<KnowledgeMember>> list(
            @PathVariable Long knowledgeBaseId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(memberService.listMembers(knowledgeBaseId, principal.userId(), principal.admin()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('knowledge:member:manage') or hasAuthority('admin:all')")
    public ApiResponse<Void> add(
            @PathVariable Long knowledgeBaseId,
            @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        memberService.addMember(knowledgeBaseId, request.userId(), request.role(), principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('knowledge:member:manage') or hasAuthority('admin:all')")
    public ApiResponse<Void> updateRole(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long userId,
            @RequestBody UpdateMemberRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        memberService.updateMemberRole(knowledgeBaseId, userId, request.role(), principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('knowledge:member:manage') or hasAuthority('admin:all')")
    public ApiResponse<Void> remove(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long userId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        memberService.removeMember(knowledgeBaseId, userId, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    public record AddMemberRequest(Long userId, Integer role) {}
    public record UpdateMemberRequest(Integer role) {}
}
