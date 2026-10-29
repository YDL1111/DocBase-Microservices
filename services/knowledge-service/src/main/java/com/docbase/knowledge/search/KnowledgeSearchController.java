package com.docbase.knowledge.search;

import com.docbase.common.core.ApiResponse;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge/search")
public class KnowledgeSearchController {
    private final KnowledgeSearchService service;
    public KnowledgeSearchController(KnowledgeSearchService service) { this.service = service; }
    public record Request(@NotNull @Positive Long knowledgeBaseId, @NotBlank @Size(max=500) String query) {}
    @PostMapping
    @PreAuthorize("hasAuthority('knowledge:document:list') or hasAuthority('admin:all')")
    public ApiResponse<List<KnowledgeSearchService.Hit>> search(@Valid @RequestBody Request request,
            @AuthenticationPrincipal KnowledgeUserPrincipal principal) {
        return ApiResponse.success(service.search(request.knowledgeBaseId(), request.query(), principal));
    }
}
