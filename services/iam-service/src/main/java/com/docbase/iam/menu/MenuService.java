package com.docbase.iam.menu;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.TokenStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MenuService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final TokenStore tokenStore;

    public MenuService(SysMenuMapper menuMapper, SysRoleMenuMapper roleMenuMapper, TokenStore tokenStore) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.tokenStore = tokenStore;
    }

    public SysMenu getById(Long menuId) {
        return menuMapper.selectById(menuId);
    }

    public List<SysMenu> listAll() {
        return menuMapper.selectList(new QueryWrapper<SysMenu>().orderByAsc("parent_id", "sort_num"));
    }

    public List<MenuNode> tree() {
        List<SysMenu> menus = listAll();
        return buildTree(menus);
    }

    @Transactional
    public Long create(SysMenu menu) {
        validateNoCircularReference(menu.getParentId(), null);
        menu.setStatus(menu.getStatus() != null ? menu.getStatus() : 1);
        menu.setIsButton(menu.getIsButton() != null ? menu.getIsButton() : 0);
        menuMapper.insert(menu);
        // New menu may affect permissions: invalidate all caches
        invalidateAllPermissions();
        return menu.getMenuId();
    }

    @Transactional
    public void update(SysMenu menu) {
        SysMenu existing = menuMapper.selectById(menu.getMenuId());
        if (existing == null) {
            throw new BusinessException("MENU_NOT_FOUND", "menu not found");
        }
        validateNoCircularReference(menu.getParentId(), menu.getMenuId());
        existing.setParentId(menu.getParentId());
        existing.setMenuName(menu.getMenuName());
        existing.setMenuType(menu.getMenuType());
        existing.setRouterName(menu.getRouterName());
        existing.setPath(menu.getPath());
        existing.setPermission(menu.getPermission());
        existing.setMetaInfo(menu.getMetaInfo());
        existing.setIsButton(menu.getIsButton());
        existing.setSortNum(menu.getSortNum());
        existing.setStatus(menu.getStatus());
        existing.setRemark(menu.getRemark());
        menuMapper.updateById(existing);
        // Permission change affects all users
        invalidateAllPermissions();
    }

    @Transactional
    public void delete(Long menuId) {
        long children = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("parent_id", menuId));
        if (children > 0) {
            throw new BusinessException("MENU_HAS_CHILDREN", "cannot delete menu with children");
        }
        // Clean up role-menu associations
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", menuId));
        menuMapper.deleteById(menuId);
        // Menu deletion affects permissions
        invalidateAllPermissions();
    }

    /**
     * Validates that setting parentId does not create a circular reference.
     */
    private void validateNoCircularReference(Long parentId, Long currentMenuId) {
        if (parentId == null || parentId == 0L) return;
        if (currentMenuId != null && parentId.equals(currentMenuId)) {
            throw new BusinessException("MENU_CIRCULAR_REF", "menu cannot be its own parent");
        }
        // Check ancestors
        Long ancestor = parentId;
        while (ancestor != null && ancestor != 0L) {
            SysMenu parent = menuMapper.selectById(ancestor);
            if (parent == null) break;
            if (currentMenuId != null && currentMenuId.equals(parent.getMenuId())) {
                throw new BusinessException("MENU_CIRCULAR_REF", "circular menu reference detected");
            }
            ancestor = parent.getParentId();
        }
    }

    /**
     * Invalidates all permission caches and bumps all auth versions.
     * Called when menu structure changes to ensure old access tokens lose their permissions.
     */
    private void invalidateAllPermissions() {
        tokenStore.evictAllPermissions();
        tokenStore.bumpAllAuthVersions();
    }

    private List<MenuNode> buildTree(List<SysMenu> menus) {
        Map<Long, MenuNode> nodeMap = new HashMap<>();
        for (SysMenu m : menus) {
            nodeMap.put(m.getMenuId(), new MenuNode(
                    m.getMenuId(), m.getParentId(), m.getMenuName(), m.getRouterName(),
                    m.getPath(), m.getPermission(), m.getMenuType(), m.getIsButton(),
                    m.getSortNum(), m.getMetaInfo(), new ArrayList<>()));
        }
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode node : nodeMap.values()) {
            if (node.parentId() == null || node.parentId() == 0L) {
                roots.add(node);
            } else {
                MenuNode parent = nodeMap.get(node.parentId());
                if (parent != null) {
                    parent.children().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        roots.sort(Comparator.comparingInt(a -> a.sortNum() != null ? a.sortNum() : 0));
        return roots;
    }

    public record MenuNode(Long menuId, Long parentId, String menuName, String routerName,
                           String path, String permission, Integer menuType, Integer isButton,
                           Integer sortNum, String metaInfo, List<MenuNode> children) {}
}
