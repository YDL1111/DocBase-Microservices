package com.docbase.knowledge.folder.controller;

import com.docbase.common.core.ApiResponse;
import com.docbase.knowledge.folder.domain.KnowledgeFolder;
import com.docbase.knowledge.folder.service.KnowledgeFolderService;
import com.docbase.knowledge.folder.service.KnowledgeFolderService.FolderNode;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/bases/{knowledgeBaseId}/folders")
public class KnowledgeFolderController {

    private final KnowledgeFolderService folderService;

    public KnowledgeFolderController(KnowledgeFolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('knowledge:folder:list') or hasAuthority('admin:all')")
    public ApiResponse<List<FolderNode>> tree(
            @PathVariable Long knowledgeBaseId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(folderService.getTree(knowledgeBaseId, principal.userId(), principal.admin()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('knowledge:folder:create') or hasAuthority('admin:all')")
    public ApiResponse<Long> create(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody CreateFolderRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setParentId(request.parentId() != null ? request.parentId() : 0L);
        folder.setName(request.name());
        folder.setSortNum(request.sortNum());
        return ApiResponse.success(folderService.create(knowledgeBaseId, folder, principal.userId(), principal.admin()));
    }

    @PutMapping("/{folderId}")
    @PreAuthorize("hasAuthority('knowledge:folder:update') or hasAuthority('admin:all')")
    public ApiResponse<Void> update(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long folderId,
            @Valid @RequestBody UpdateFolderRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeFolder updates = new KnowledgeFolder();
        updates.setParentId(request.parentId());
        updates.setName(request.name());
        updates.setSortNum(request.sortNum());
        folderService.update(knowledgeBaseId, folderId, updates, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{folderId}")
    @PreAuthorize("hasAuthority('knowledge:folder:delete') or hasAuthority('admin:all')")
    public ApiResponse<Void> delete(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long folderId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        folderService.delete(knowledgeBaseId, folderId, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    public record CreateFolderRequest(
            Long parentId,
            @NotBlank @Size(max = 128) String name,
            Integer sortNum) {}

    public record UpdateFolderRequest(
            Long parentId,
            @Size(max = 128) String name,
            Integer sortNum) {}
}
