package com.docbase.knowledge.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge/bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('knowledge:base:list') or hasAuthority('admin:all')")
    public ApiResponse<Page<KnowledgeBase>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(knowledgeBaseService.listForUser(current, size, principal.userId(), principal.admin()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('knowledge:base:list') or hasAuthority('admin:all')")
    public ApiResponse<KnowledgeBase> get(
            @PathVariable Long id,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(knowledgeBaseService.getById(id, principal.userId(), principal.admin()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('knowledge:base:create') or hasAuthority('admin:all')")
    public ApiResponse<Long> create(
            @Valid @RequestBody CreateBaseRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeBase base = new KnowledgeBase();
        base.setName(request.name());
        base.setDescription(request.description());
        base.setVisibility(request.visibility());
        return ApiResponse.success(knowledgeBaseService.create(base, principal.userId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('knowledge:base:update') or hasAuthority('admin:all')")
    public ApiResponse<Void> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBaseRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeBase updates = new KnowledgeBase();
        updates.setName(request.name());
        updates.setDescription(request.description());
        updates.setVisibility(request.visibility());
        updates.setStatus(request.status());
        knowledgeBaseService.update(id, updates, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('knowledge:base:delete') or hasAuthority('admin:all')")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        knowledgeBaseService.delete(id, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    public record CreateBaseRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description,
            Integer visibility) {}

    public record UpdateBaseRequest(
            @Size(max = 128) String name,
            @Size(max = 512) String description,
            Integer visibility,
            Integer status) {}
}
