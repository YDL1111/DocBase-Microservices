package com.docbase.iam.menu;

import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.TokenStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuTreeTest {

    @Test
    void menuNodeRecordHoldsChildren() {
        MenuService.MenuNode child = new MenuService.MenuNode(
                2L, 1L, "用户", "", "", "", 1, 0, 1, "{}", List.of());
        MenuService.MenuNode parent = new MenuService.MenuNode(
                1L, 0L, "系统", "", "", "", 2, 0, 1, "{}", List.of(child));

        assertEquals(1, parent.children().size());
        assertEquals("用户", parent.children().get(0).menuName());
    }

    @Test
    void treeBuildsCorrectlyFromFlatList() {
        SysMenuMapper mapper = mock(SysMenuMapper.class);
        SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
        TokenStore tokenStore = mock(TokenStore.class);
        MenuService service = new MenuService(mapper, roleMenuMapper, tokenStore);

        SysMenu root = menu(1L, 0L, "系统管理", 1);
        SysMenu user = menu(2L, 1L, "用户管理", 1);
        SysMenu role = menu(3L, 1L, "角色管理", 2);
        SysMenu log = menu(4L, 0L, "日志管理", 2);

        when(mapper.selectList(any())).thenReturn(List.of(root, user, role, log));

        List<MenuService.MenuNode> tree = service.tree();

        assertEquals(2, tree.size()); // 系统管理 and 日志管理 are roots
        MenuService.MenuNode sysNode = tree.stream().filter(n -> n.menuId().equals(1L)).findFirst().orElseThrow();
        assertEquals(2, sysNode.children().size()); // 用户管理 and 角色管理
    }

    private SysMenu menu(Long id, Long parentId, String name, int sort) {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setParentId(parentId);
        m.setMenuName(name);
        m.setSortNum(sort);
        m.setMenuType(1);
        return m;
    }
}
