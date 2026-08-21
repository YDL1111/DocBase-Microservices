package com.docbase.knowledge.document.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.service.KnowledgeDocumentUploadService;
import com.docbase.knowledge.document.service.KnowledgeDocumentService;
import com.docbase.knowledge.config.DocumentUploadProperties;

import java.util.List;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentUploadService uploadService;
    private final DocumentUploadProperties uploadProperties;

    public KnowledgeDocumentController(KnowledgeDocumentService documentService,
                                       KnowledgeDocumentUploadService uploadService,
                                       DocumentUploadProperties uploadProperties) {
        this.documentService = documentService;
        this.uploadService = uploadService;
        this.uploadProperties = uploadProperties;
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

    @GetMapping("/documents/{documentId}/content")
    @PreAuthorize("hasAuthority('knowledge:document:list') or hasAuthority('admin:all')")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable Long documentId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        KnowledgeDocumentService.DocumentContent content = documentService.openContent(
                documentId, principal.userId(), principal.admin());
        KnowledgeDocument document = content.document();
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(document.getContentType());
        } catch (Exception ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String filename = document.getOriginalFilename() == null ? "document" : document.getOriginalFilename();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff");
        if (document.getFileSize() != null && document.getFileSize() >= 0) {
            response.contentLength(document.getFileSize());
        }
        return response.body(new InputStreamResource(content.inputStream()));
    }

    /**
     * Returns the document IDs the current user is allowed to see in the knowledge base,
     * for AI chat to scope RAG retrieval. The chat service must never accept these IDs
     * from the client — they are always computed here from the verified JWT identity.
     *
     * <p>Only published, successfully-ingested, non-deleted documents are considered.
     * Visibility follows the document visibility rules (PUBLIC/PRIVATE-with-ACL/DEPT-fail-closed).
     */
    @GetMapping("/bases/{knowledgeBaseId}/visible-document-ids")
    @PreAuthorize("hasAuthority('knowledge:document:list') or hasAuthority('admin:all')")
    public ApiResponse<List<Long>> visibleDocumentIds(
            @PathVariable Long knowledgeBaseId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(documentService.findVisibleDocumentIds(
                knowledgeBaseId, principal.userId(), principal.admin()));
    }

    @PostMapping("/bases/{knowledgeBaseId}/documents")
    @PreAuthorize("hasAuthority('knowledge:document:register:internal') or hasAuthority('admin:all')")
    public ApiResponse<Long> create(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody CreateDocumentRequest request,
            @RequestHeader(value = "X-Knowledge-Internal-Key", required = false) String internalKey,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        requireInternalRegistrationKey(internalKey);
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

    @PostMapping(value = "/bases/{knowledgeBaseId}/documents/upload", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('knowledge:document:create') or hasAuthority('admin:all')")
    public ApiResponse<Long> upload(
            @PathVariable Long knowledgeBaseId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) Integer visibility,
            @RequestParam(defaultValue = "true") boolean publishForChat,
            @RequestParam String clientRequestId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(uploadService.upload(knowledgeBaseId, file, title, folderId, visibility,
                publishForChat, clientRequestId, principal.userId(), principal.admin()));
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

    @PostMapping("/documents/{documentId}/reingest")
    @PreAuthorize("hasAuthority('knowledge:document:update') or hasAuthority('admin:all')")
    public ApiResponse<Void> reingest(
            @PathVariable Long documentId,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        documentService.reingest(documentId, principal.userId(), principal.admin());
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

    private void requireInternalRegistrationKey(String suppliedKey) {
        String configuredKey = uploadProperties.getInternalRegistrationApiKey();
        if (configuredKey == null || configuredKey.isBlank() || suppliedKey == null
                || !MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("internal registration required");
        }
    }

    public record UpdateDocumentRequest(
            @Size(max = 256) String title,
            Long folderId,
            Integer visibility,
            Integer status) {}
}
