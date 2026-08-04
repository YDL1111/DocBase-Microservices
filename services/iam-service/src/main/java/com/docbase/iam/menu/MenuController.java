package com.docbase.iam.menu;

import com.docbase.common.core.ApiResponse;
import com.docbase.iam.menu.domain.SysMenu;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:create') or hasAuthority('admin:all')")
    ApiResponse<Long> create(@Valid @RequestBody MenuRequest request) {
        SysMenu menu = new SysMenu();
        menu.setParentId(request.parentId());
        menu.setMenuName(request.menuName());
        menu.setMenuType(request.menuType());
        menu.setRouterName(request.routerName());
        menu.setPath(request.path());
        menu.setPermission(request.permission());
        menu.setMetaInfo(request.metaInfo());
        menu.setIsButton(request.isButton());
        menu.setSortNum(request.sortNum());
        menu.setStatus(request.status());
        menu.setRemark(request.remark());
        return ApiResponse.success(menuService.create(menu));
    }

    @PutMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:update') or hasAuthority('admin:all')")
    ApiResponse<Void> update(@PathVariable Long menuId, @Valid @RequestBody MenuRequest request) {
        SysMenu menu = new SysMenu();
        menu.setMenuId(menuId);
        menu.setParentId(request.parentId());
        menu.setMenuName(request.menuName());
        menu.setMenuType(request.menuType());
        menu.setRouterName(request.routerName());
        menu.setPath(request.path());
        menu.setPermission(request.permission());
        menu.setMetaInfo(request.metaInfo());
        menu.setIsButton(request.isButton());
        menu.setSortNum(request.sortNum());
        menu.setStatus(request.status());
        menu.setRemark(request.remark());
        menuService.update(menu);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:delete') or hasAuthority('admin:all')")
    ApiResponse<Void> delete(@PathVariable Long menuId) {
        menuService.delete(menuId);
        return ApiResponse.success(null);
    }

    public record MenuRequest(
            @NotNull Long parentId,
            @NotBlank String menuName,
            @NotNull Integer menuType,
            String routerName,
            String path,
            String permission,
            String metaInfo,
            Integer isButton,
            Integer sortNum,
            Integer status,
            String remark) {}
}
