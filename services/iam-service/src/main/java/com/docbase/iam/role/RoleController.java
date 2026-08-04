package com.docbase.iam.role;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.iam.role.domain.SysRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:role:list') or hasAuthority('admin:all')")
    ApiResponse<Page<SysRole>> list(@RequestParam(defaultValue = "1") long current,
                                   @RequestParam(defaultValue = "20") long size,
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
    ApiResponse<SysRole> get(@PathVariable Long roleId) {
        return ApiResponse.success(roleService.getById(roleId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:role:create') or hasAuthority('admin:all')")
    ApiResponse<Long> create(@Valid @RequestBody RoleRequest request) {
        SysRole role = new SysRole();
        role.setRoleName(request.roleName());
        role.setRoleKey(request.roleKey());
        role.setRoleSort(request.roleSort());
        role.setDataScope(request.dataScope());
        role.setRemark(request.remark());
        role.setStatus(request.status());
        return ApiResponse.success(roleService.create(role, request.menuIds()));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:update') or hasAuthority('admin:all')")
    ApiResponse<Void> update(@PathVariable Long roleId, @Valid @RequestBody RoleRequest request) {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setRoleName(request.roleName());
        role.setRoleKey(request.roleKey());
        role.setRoleSort(request.roleSort());
        role.setDataScope(request.dataScope());
        role.setRemark(request.remark());
        roleService.update(role, request.menuIds());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:delete') or hasAuthority('admin:all')")
    ApiResponse<Void> delete(@PathVariable Long roleId) {
        roleService.delete(roleId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{roleId}/status")
    @PreAuthorize("hasAuthority('system:role:update') or hasAuthority('admin:all')")
    ApiResponse<Void> changeStatus(@PathVariable Long roleId, @RequestBody Map<String, Integer> body) {
        roleService.changeStatus(roleId, body.get("status"));
        return ApiResponse.success(null);
    }

    @GetMapping("/{roleId}/menus")
    @PreAuthorize("hasAuthority('system:role:list') or hasAuthority('admin:all')")
    ApiResponse<List<Long>> getMenus(@PathVariable Long roleId) {
        return ApiResponse.success(roleService.getMenuIds(roleId));
    }

    public record RoleRequest(
            @NotBlank String roleName,
            @NotBlank String roleKey,
            Integer roleSort,
            Integer dataScope,
            Integer status,
            String remark,
            List<Long> menuIds) {}
}
