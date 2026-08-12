package com.docbase.iam.role;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.dto.AssignRoleMenusRequest;
import com.docbase.iam.role.dto.ChangeRoleStatusRequest;
import com.docbase.iam.role.dto.CreateRoleRequest;
import com.docbase.iam.role.dto.UpdateRoleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/roles")
@Validated
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:role:list') or hasAuthority('admin:all')")
    ApiResponse<Page<SysRole>> list(@RequestParam(defaultValue = "1") @Min(1) long current,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
                                   @RequestParam(required = false) String roleName) {
        return ApiResponse.success(roleService.page(current, size, roleName));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('system:role:list') or hasAuthority('admin:all')")
    ApiResponse<List<SysRole>> all() {
        return ApiResponse.success(roleService.listAll());
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:list') or hasAuthority('admin:all')")
    ApiResponse<SysRole> get(@PathVariable @Min(1) Long roleId) {
        return ApiResponse.success(roleService.getById(roleId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:role:create') or hasAuthority('admin:all')")
    ApiResponse<Long> create(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.success(roleService.create(request));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:update') or hasAuthority('admin:all')")
    ApiResponse<Void> update(@PathVariable @Min(1) Long roleId,
                              @Valid @RequestBody UpdateRoleRequest request) {
        roleService.update(roleId, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:delete') or hasAuthority('admin:all')")
    ApiResponse<Void> delete(@PathVariable @Min(1) Long roleId) {
        roleService.delete(roleId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{roleId}/status")
    @PreAuthorize("hasAuthority('system:role:update') or hasAuthority('admin:all')")
    ApiResponse<Void> changeStatus(@PathVariable @Min(1) Long roleId,
                                   @Valid @RequestBody ChangeRoleStatusRequest request) {
        roleService.changeStatus(roleId, request);
        return ApiResponse.success(null);
    }

    @PutMapping("/{roleId}/menus")
    @PreAuthorize("hasAuthority('system:role:update') or hasAuthority('admin:all')")
    ApiResponse<Void> assignMenus(@PathVariable @Min(1) Long roleId,
                                  @Valid @RequestBody AssignRoleMenusRequest request) {
        roleService.assignMenus(roleId, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/{roleId}/menus")
    @PreAuthorize("hasAuthority('system:role:list') or hasAuthority('admin:all')")
    ApiResponse<List<Long>> getMenus(@PathVariable @Min(1) Long roleId) {
        return ApiResponse.success(roleService.getMenuIds(roleId));
    }
}
