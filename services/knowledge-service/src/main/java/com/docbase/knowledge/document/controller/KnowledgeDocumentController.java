package com.docbase.knowledge.document.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.service.KnowledgeDocumentService;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    public KnowledgeDocumentController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/bases/{knowledgeBaseId}/documents")
    @PreAuthorize("hasAuthority('knowledge:document:list') or hasAuthority('admin:all')")
    public ApiResponse<Page<KnowledgeDocument>> listByBase(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(documentService.listByBaseId(knowledgeBaseId, current, size, principal.userId(), principal.admin()));
    }

    @GetMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('knowledge:document:list') or hasAuthority('admin:all')")
    public ApiResponse<KnowledgeDocument> get(
            @PathVariable Long documentId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(documentService.getById(documentId, principal.userId(), principal.admin()));
    }

    @PostMapping("/bases/{knowledgeBaseId}/documents")
    @PreAuthorize("hasAuthority('knowledge:document:create') or hasAuthority('admin:all')")
    public ApiResponse<Long> create(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody CreateDocumentRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFolderId(request.folderId() != null ? request.folderId() : 0L);
        doc.setTitle(request.title());
        doc.setOriginalFilename(request.originalFilename());
        doc.setObjectKey(request.objectKey());
        doc.setContentType(request.contentType());
        doc.setFileSize(request.fileSize());
        doc.setChecksum(request.checksum());
        doc.setVisibility(request.visibility());
        return ApiResponse.success(documentService.registerDocument(knowledgeBaseId, doc, principal.userId(), principal.admin()));
    }

    @PutMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('knowledge:document:update') or hasAuthority('admin:all')")
    public ApiResponse<Void> update(
            @PathVariable Long documentId,
            @Valid @RequestBody UpdateDocumentRequest request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeDocument updates = new KnowledgeDocument();
        updates.setTitle(request.title());
        updates.setFolderId(request.folderId());
        updates.setVisibility(request.visibility());
        updates.setStatus(request.status());
        documentService.update(documentId, updates, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('knowledge:document:delete') or hasAuthority('admin:all')")
    public ApiResponse<Void> delete(
            @PathVariable Long documentId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        documentService.delete(documentId, principal.userId(), principal.admin());
        return ApiResponse.success(null);
    }

    public record CreateDocumentRequest(
            Long folderId,
            @NotBlank @Size(max = 256) String title,
            @Size(max = 512) String originalFilename,
            @Size(max = 512) String objectKey,
            @Size(max = 128) String contentType,
            Long fileSize,
            String checksum,
            Integer visibility) {}

    public record UpdateDocumentRequest(
            @Size(max = 256) String title,
            Long folderId,
            Integer visibility,
            Integer status) {}
}
