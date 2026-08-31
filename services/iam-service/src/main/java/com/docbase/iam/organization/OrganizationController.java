package com.docbase.iam.organization;

import com.docbase.common.core.ApiResponse;
import com.docbase.iam.organization.domain.SysOrganization;
import com.docbase.iam.organization.dto.OrganizationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/organizations")
@Validated
public class OrganizationController {
    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:org:list') or hasAuthority('admin:all')")
    ApiResponse<List<SysOrganization>> list() { return ApiResponse.success(organizationService.list()); }

    @PostMapping
    @PreAuthorize("hasAuthority('system:org:create') or hasAuthority('admin:all')")
    ApiResponse<Long> create(@Valid @RequestBody OrganizationRequest request) {
        return ApiResponse.success(organizationService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:org:update') or hasAuthority('admin:all')")
    ApiResponse<Void> update(@PathVariable @Min(1) Long id, @Valid @RequestBody OrganizationRequest request) {
        organizationService.update(id, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:org:delete') or hasAuthority('admin:all')")
    ApiResponse<Void> delete(@PathVariable @Min(1) Long id) {
        organizationService.delete(id);
        return ApiResponse.success(null);
    }
}
