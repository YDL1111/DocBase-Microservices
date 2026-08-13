package com.docbase.iam.menu;

import com.docbase.common.core.ApiResponse;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.dto.ChangeMenuStatusRequest;
import com.docbase.iam.menu.dto.CreateMenuRequest;
import com.docbase.iam.menu.dto.MenuOwnerRolesRequest;
import com.docbase.iam.menu.dto.UpdateMenuRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:menu:list') or hasAuthority('admin:all')")
    ApiResponse<List<SysMenu>> list() {
        return ApiResponse.success(menuService.listAll());
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:menu:list') or hasAuthority('admin:all')")
    ApiResponse<List<MenuService.MenuNode>> tree() {
        return ApiResponse.success(menuService.tree());
    }

    @GetMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:list') or hasAuthority('admin:all')")
    ApiResponse<SysMenu> get(@PathVariable Long menuId) {
        return ApiResponse.success(menuService.getById(menuId));
    }

    /**
     * 查询菜单的有效 owner（所有者角色）ID 列表。仅超级管理员（admin:all）可调用。
     */
    @GetMapping("/{menuId}/owners")
    @PreAuthorize("hasAuthority('admin:all')")
    ApiResponse<List<Long>> getOwners(@PathVariable Long menuId) {
        return ApiResponse.success(menuService.getOwners(menuId));
    }

    /**
     * 全量替换（转让）菜单的 owner 角色。仅超级管理员（admin:all）可调用。
     *
     * <p>空列表表示超级管理员明确选择"系统托管"。该接口只写 sys_menu_owner_role，
     * 不写 sys_role_menu、不授予任何 permission。
     */
    @PutMapping("/{menuId}/owners")
    @PreAuthorize("hasAuthority('admin:all')")
    ApiResponse<Void> replaceOwners(@PathVariable Long menuId,
                                    @Valid @RequestBody MenuOwnerRolesRequest request) {
        menuService.replaceOwners(menuId, request.roleIds());
        return ApiResponse.success(null);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:create') or hasAuthority('admin:all')")
    ApiResponse<Long> create(@Valid @RequestBody CreateMenuRequest request) {
        return ApiResponse.success(menuService.create(request));
    }

    @PutMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:update') or hasAuthority('admin:all')")
    ApiResponse<Void> update(@PathVariable Long menuId, @Valid @RequestBody UpdateMenuRequest request) {
        menuService.update(menuId, request);
        return ApiResponse.success(null);
    }

    /**
     * 启用/停用菜单。停用后该菜单从用户可见树与权限集消失（关联保留，重启用即恢复）。
     */
    @PutMapping("/{menuId}/status")
    @PreAuthorize("hasAuthority('system:menu:update') or hasAuthority('admin:all')")
    ApiResponse<Void> changeStatus(@PathVariable Long menuId,
                                   @Valid @RequestBody ChangeMenuStatusRequest request) {
        menuService.changeStatus(menuId, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:delete') or hasAuthority('admin:all')")
    ApiResponse<Void> delete(@PathVariable Long menuId) {
        menuService.delete(menuId);
        return ApiResponse.success(null);
    }
}
