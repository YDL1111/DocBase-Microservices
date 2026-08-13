package com.docbase.iam.menu;

import com.docbase.common.core.BusinessException;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.domain.SysMenuOwnerRole;
import com.docbase.iam.menu.dto.ChangeMenuStatusRequest;
import com.docbase.iam.menu.dto.CreateMenuRequest;
import com.docbase.iam.menu.dto.UpdateMenuRequest;
import com.docbase.iam.menu.mapper.MenuOwnerMutexMapper;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.menu.mapper.SysMenuOwnerRoleMapper;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.TokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 菜单管理服务层安全测试 —— 聚焦字段校验、系统保护、权限防提权、父子关系与受影响用户失效。
 *
 * <p>采用纯 Mockito（不启动 Spring 上下文），通过手动设置 SecurityContext 模拟调用者身份。
 */
class MenuServiceTest {

    private SysMenuMapper menuMapper;
    private SysRoleMenuMapper roleMenuMapper;
    private SysMenuOwnerRoleMapper ownerRoleMapper;
    private MenuOwnerMutexMapper ownerMutexMapper;
    private TokenStore tokenStore;
    private MenuService menuService;

    @BeforeEach
    void setUp() {
        menuMapper = mock(SysMenuMapper.class);
        roleMenuMapper = mock(SysRoleMenuMapper.class);
        ownerRoleMapper = mock(SysMenuOwnerRoleMapper.class);
        ownerMutexMapper = mock(MenuOwnerMutexMapper.class);
        tokenStore = mock(TokenStore.class);
        // owner 生命周期互斥守卫默认存在（迁移已执行），避免各用例重复桩装。
        when(ownerMutexMapper.lockGuardRow()).thenReturn(1);
        menuService = new MenuService(menuMapper, roleMenuMapper, ownerRoleMapper, ownerMutexMapper,
                mock(OwnerLifecycleLockHook.class), tokenStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ========================= 辅助方法 ========================= */

    private void setCaller(long callerId, boolean admin, String... perms) {
        Set<String> permissions = admin ? Set.of(IamUserPrincipal.ADMIN_ALL_PERMISSION) : Set.of(perms);
        IamUserPrincipal principal = new IamUserPrincipal(callerId, "caller" + callerId, admin, permissions);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private IamUserPrincipal currentPrincipal() {
        return (IamUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private CreateMenuRequest createReq(Long parentId, String name, int type,
                                        String routerName, String path, String permission) {
        return new CreateMenuRequest(parentId, name, type, routerName, path, permission,
                "{}", 0, 1, 1, null);
    }

    private UpdateMenuRequest updateReq(Long parentId, String name, int type,
                                        String routerName, String path, String permission) {
        // 注意：UpdateMenuRequest 不再含 status 字段（状态必须走 /status 端点），
        // 故末尾 4 个字段为 metaInfo / isButton / sortNum / remark。
        return new UpdateMenuRequest(parentId, name, type, routerName, path, permission,
                "{}", 0, 1, null);
    }

    private SysMenu menu(long id, long parentId, int type, int isButton, int status, int isSystem) {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setParentId(parentId);
        m.setMenuName("M" + id);
        m.setMenuType(type);
        m.setRouterName(type == 3 ? "" : "Router" + id);
        m.setPath(type == 3 ? "" : "/path" + id);
        m.setIsButton(isButton);
        m.setStatus(status);
        m.setIsSystem(isSystem);
        m.setDeleted(0);
        return m;
    }

    private void stubParent(long parentId, long parentParentId, int status) {
        SysMenu parent = menu(parentId, parentParentId, 2, 0, status, 0);
        when(menuMapper.selectById(parentId)).thenReturn(parent);
    }

    /* ========================= create ========================= */

    @Test
    void create_超级管理员可创建合法菜单() {
        setCaller(1, true);
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(100L);
            return 1;
        });

        Long id = menuService.create(createReq(0L, "目录", 2, "Dir", "/dir", ""));

        assertEquals(100L, id);
        verify(menuMapper).insert(any(SysMenu.class));
        // 新建菜单尚无角色关联，不应失效任何用户
        verify(tokenStore, never()).evictPermissions(any());
        verify(tokenStore, never()).bumpAuthVersion(any());
    }

    @Test
    void create_接口创建_isSystem_强制为_0() {
        setCaller(1, true);
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(101L);
            return 1;
        });

        menuService.create(createReq(0L, "目录2", 2, "Dir2", "/dir2", ""));

        verify(menuMapper).insert(argThat((SysMenu m) ->
                m.getIsSystem() != null && m.getIsSystem() == 0));
    }

    @Test
    void create_permission_为_admin_all_被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "X", 2, "X", "/x", "admin:all")));
        assertEquals("PERMISSION_NOT_GRANTABLE", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void create_permission_含空白_admin_all_被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "X", 2, "X", "/x", "  admin:all  ")));
        assertEquals("PERMISSION_NOT_GRANTABLE", ex.code());
    }

    @Test
    void create_普通管理员创建自己没有的_permission_被拒绝() {
        // 调用者只有 system:menu:list，却要用 system:user:delete
        setCaller(2, false, "system:menu:list");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "X", 2, "X", "/x", "system:user:delete")));
        assertEquals("PERMISSION_NOT_SUBSET", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void create_旧权限映射后不误判越权() {
        // 调用者 JWT 持有新格式 system:role:update；DTO 用旧格式 system:role:edit。
        // 顶级节点仅超级管理员可建，故在父目录下创建，并继承父目录的角色关联。
        setCaller(2, false, "system:role:update");
        SysMenu parent = menu(10L, 0L, 2, 0, 1, 0); // 空 permission 目录，无后代
        when(menuMapper.selectById(10L)).thenReturn(parent);
        when(menuMapper.selectChildIds(10L)).thenReturn(Set.of());
        when(ownerRoleMapper.countOwnerLinks(2L, 10L)).thenReturn(1); // assertCanWriteUnder 通过
        when(ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(2L, 10L)).thenReturn(Set.of(50L)); // 继承
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(102L);
            return 1;
        });

        Long id = menuService.create(createReq(10L, "X", 2, "X", "/x", "system:role:edit"));
        assertEquals(102L, id);
        verify(menuMapper).insert(any(SysMenu.class));
        // 继承的所有者角色关联已建立（写入独立的归属表，而非 sys_role_menu，避免权限扩散）
        verify(ownerRoleMapper).insert(argThat((SysMenuOwnerRole mor) ->
                mor.getRoleId().equals(50L) && mor.getMenuId().equals(102L)));
        // 关键：不得写入 sys_role_menu，否则继承角色的所有成员都会获得新菜单的 permission
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));
    }

    @Test
    void create_父节点不存在被拒绝() {
        setCaller(1, true);
        when(menuMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(999L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_PARENT_NOT_FOUND", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void create_父节点已删除被拒绝() {
        setCaller(1, true);
        SysMenu deletedParent = menu(10L, 0L, 2, 0, 1, 0);
        deletedParent.setDeleted(1);
        when(menuMapper.selectById(10L)).thenReturn(deletedParent);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(10L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_PARENT_NOT_FOUND", ex.code());
    }

    @Test
    void create_父节点停用被拒绝() {
        setCaller(1, true);
        stubParent(10L, 0L, 0); // status=0 停用

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(10L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_PARENT_DISABLED", ex.code());
    }

    @Test
    void create_父节点是按钮被拒绝() {
        setCaller(1, true);
        SysMenu buttonParent = menu(10L, 0L, 3, 1, 1, 0);
        when(menuMapper.selectById(10L)).thenReturn(buttonParent);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(10L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_PARENT_IS_BUTTON", ex.code());
    }

    @Test
    void update_移到自己的后代节点被拒绝() {
        // 树 1→2→3；把菜单 1 的 parent 设为 3（自己的后代）→ 成环，应拒绝。
        // 校验 isDescendant(currentMenuId=1, parentId=3)：从 1 向下 BFS 应能到达 3。
        setCaller(1, true);
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, 2, 0, 1, 0));   // 现有菜单 1
        when(menuMapper.selectById(3L)).thenReturn(menu(3L, 2L, 2, 0, 1, 0));   // 候选父 3
        when(menuMapper.selectById(2L)).thenReturn(menu(2L, 1L, 2, 0, 1, 0));
        when(menuMapper.selectChildIds(1L)).thenReturn(Set.of(2L));
        when(menuMapper.selectChildIds(2L)).thenReturn(Set.of(3L));
        when(menuMapper.selectChildIds(3L)).thenReturn(Set.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(1L, updateReq(3L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_CIRCULAR_REF", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void create_父链未超深允许() {
        // parentId=10 的父链深度为 0（parentParentId=0），新建子节点深度 1，未超 MAX_DEPTH=6。
        setCaller(1, true);
        stubParent(10L, 0L, 1);
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(200L);
            return 1;
        });

        Long id = menuService.create(createReq(10L, "X", 2, "X", "/x", ""));
        assertEquals(200L, id);
    }

    @Test
    void create_父链已超深被拒绝() {
        // 父链 20→21→22→23→24→25→26→0 共 6 条边（depthToRoot(20)=6）。
        // 在 20 下新建子节点 → 6 + 1 = 7 > MAX_DEPTH=6 → 应拒绝（create 同样校验深度）。
        setCaller(1, true);
        when(menuMapper.selectById(20L)).thenReturn(menu(20L, 21L, 2, 0, 1, 0));
        when(menuMapper.selectById(21L)).thenReturn(menu(21L, 22L, 2, 0, 1, 0));
        when(menuMapper.selectById(22L)).thenReturn(menu(22L, 23L, 2, 0, 1, 0));
        when(menuMapper.selectById(23L)).thenReturn(menu(23L, 24L, 2, 0, 1, 0));
        when(menuMapper.selectById(24L)).thenReturn(menu(24L, 25L, 2, 0, 1, 0));
        when(menuMapper.selectById(25L)).thenReturn(menu(25L, 26L, 2, 0, 1, 0));
        when(menuMapper.selectById(26L)).thenReturn(menu(26L, 0L, 2, 0, 1, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(20L, "Deep", 2, "Deep", "/deep", "")));
        assertEquals("MENU_DEPTH_EXCEEDED", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void create_按钮节点_permission_为空被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "按钮", 3, "", "", "")));
        assertEquals("MENU_BUTTON_NEEDS_PERMISSION", ex.code());
    }

    @Test
    void create_按钮节点_routerName_非空被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "按钮", 3, "SomeRouter", "", "sys:x")));
        assertEquals("MENU_BUTTON_NO_ROUTER", ex.code());
    }

    @Test
    void create_菜单节点_routerName_为空被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "菜单", 1, "", "/p", "")));
        assertEquals("MENU_NEEDS_ROUTER", ex.code());
    }

    @Test
    void create_菜单节点_path_为空被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "菜单", 1, "Menu", "", "")));
        assertEquals("MENU_NEEDS_PATH", ex.code());
    }

    @Test
    void create_routerName_格式非法被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "菜单", 1, "123bad", "/p", "")));
        assertEquals("MENU_ROUTER_INVALID", ex.code());
    }

    @Test
    void create_path_格式非法被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "菜单", 1, "Menu", "no-leading-slash", "")));
        assertEquals("MENU_PATH_INVALID", ex.code());
    }

    @Test
    void create_permission_格式非法被拒绝() {
        setCaller(1, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "菜单", 1, "Menu", "/p", "BAD PERM")));
        assertEquals("MENU_PERMISSION_INVALID", ex.code());
    }

    @Test
    void create_metaInfo_非法_JSON_被拒绝() {
        setCaller(1, true);
        CreateMenuRequest req = new CreateMenuRequest(0L, "菜单", 1, "Menu", "/p",
                "", "{not json}", 0, 1, 1, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(req));
        assertEquals("MENU_METAINFO_INVALID", ex.code());
    }

    @Test
    void create_metaInfo_非对象_JSON_被拒绝() {
        setCaller(1, true);
        CreateMenuRequest req = new CreateMenuRequest(0L, "菜单", 1, "Menu", "/p",
                "", "[1,2,3]", 0, 1, 1, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(req));
        assertEquals("MENU_METAINFO_INVALID", ex.code());
    }

    /* ========================= update ========================= */

    @Test
    void update_普通管理员不能操作系统保留菜单() {
        setCaller(2, false, "system:menu:update");
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, 2, 0, 1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(1L, updateReq(0L, "X", 2, "X", "/x", "")));
        // 为防枚举，统一返回 MENU_NOT_FOUND
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void update_超级管理员可更新系统菜单() {
        setCaller(1, true);
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, 2, 0, 1, 1));
        when(menuMapper.selectUserIdsByMenuId(1L)).thenReturn(Set.of());

        menuService.update(1L, updateReq(0L, "改名", 2, "SystemManage", "/system", ""));

        verify(menuMapper).updateById(any(SysMenu.class));
    }

    @Test
    void update_修改权限为调用者没有的被拒绝() {
        // 目标节点（menuId=5）无自身 permission、无后代 → 边界为空，归属改由角色关联判定。
        // 授予调用者到该菜单的角色关联以通过资源级校验，随后在"新 permission 子集校验"被拒绝。
        setCaller(2, false, "system:menu:update");
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 0L, 2, 0, 1, 0));
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(0L, "X", 2, "X", "/x", "system:user:delete")));
        assertEquals("PERMISSION_NOT_SUBSET", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void update_清空权限_降权_允许() {
        // 调用者持有目标菜单当前的 permission（system:user:delete），资源级校验通过；
        // 把 permission 清空（设为空）——降权，不扩大权限，允许。
        setCaller(2, false, "system:menu:update", "system:user:delete");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("system:user:delete");
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // 调用者是目标菜单 owner
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        menuService.update(5L, updateReq(0L, "X", 2, "X", "/x", ""));

        verify(menuMapper).updateById(argThat((SysMenu m) -> m.getPermission() == null));
    }

    @Test
    void update_资源级未持有目标当前权限被拒绝() {
        // 调用者只有 system:menu:update，但目标菜单当前 permission 为 system:user:delete（调用者没有）。
        // 资源级校验应拒绝（MENU_NOT_FOUND，防枚举），即使新 permission 为空（降权）也不行。
        setCaller(2, false, "system:menu:update");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("system:user:delete");
        when(menuMapper.selectById(5L)).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(0L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void update_不修改状态_停用含子节点父节点无法通过更新接口绕过() {
        // UpdateMenuRequest 已不含 status 字段，普通更新接口无法改写 status，
        // 从而无法绕过 changeStatus 的"停用含启用子节点目录"校验。
        // 验证：更新后实体 status 保持原值（update 不再触碰 status）。
        setCaller(1, true);
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setStatus(1); // 原状态：启用
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        menuService.update(5L, updateReq(0L, "改名", 2, "Router5", "/path5", ""));

        verify(menuMapper).updateById(argThat((SysMenu m) ->
                m.getStatus() != null && m.getStatus() == 1));
    }

    @Test
    void update_菜单不存在被拒绝() {
        setCaller(1, true);
        when(menuMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(999L, updateReq(0L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void update_移动到超深位置被拒绝() {
        // parentId=20 的链有 6 条边到根（20→21→22→23→24→25→26→0），currentMenuId=30 是叶子。
        // depthToRoot(20)=6, depthFromRoot(30)=0 → 6+1+0=7 > MAX_DEPTH=6 → 拒绝。
        setCaller(1, true);
        when(menuMapper.selectById(30L)).thenReturn(menu(30L, 0L, 2, 0, 1, 0));
        when(menuMapper.selectById(20L)).thenReturn(menu(20L, 21L, 2, 0, 1, 0));
        when(menuMapper.selectById(21L)).thenReturn(menu(21L, 22L, 2, 0, 1, 0));
        when(menuMapper.selectById(22L)).thenReturn(menu(22L, 23L, 2, 0, 1, 0));
        when(menuMapper.selectById(23L)).thenReturn(menu(23L, 24L, 2, 0, 1, 0));
        when(menuMapper.selectById(24L)).thenReturn(menu(24L, 25L, 2, 0, 1, 0));
        when(menuMapper.selectById(25L)).thenReturn(menu(25L, 26L, 2, 0, 1, 0));
        when(menuMapper.selectById(26L)).thenReturn(menu(26L, 0L, 2, 0, 1, 0));
        when(menuMapper.selectChildIds(20L)).thenReturn(Set.of());
        when(menuMapper.selectChildIds(30L)).thenReturn(Set.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(30L, updateReq(20L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_DEPTH_EXCEEDED", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /* ========================= delete ========================= */

    @Test
    void delete_有子节点被拒绝() {
        setCaller(1, true);
        when(menuMapper.selectById(10L)).thenReturn(menu(10L, 0L, 2, 0, 1, 0));
        when(menuMapper.countChildren(10L)).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(10L));
        assertEquals("MENU_HAS_CHILDREN", ex.code());
        verify(menuMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_系统菜单任何人不可删() {
        setCaller(1, true);
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, 2, 0, 1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(1L));
        // 为防枚举统一返回 MENU_NOT_FOUND
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_先捕获受影响用户再删除关联() {
        setCaller(1, true);
        when(menuMapper.selectById(10L)).thenReturn(menu(10L, 0L, 2, 0, 1, 0));
        when(menuMapper.countChildren(10L)).thenReturn(0);
        when(menuMapper.selectUserIdsByMenuId(10L)).thenReturn(Set.of(50L, 51L));

        menuService.delete(10L);

        verify(roleMenuMapper).delete(any());
        // 删除菜单时同步清理该菜单的所有者归属关系，避免归属孤儿行残留。
        verify(ownerRoleMapper).delete(any());
        verify(menuMapper).deleteById(10L);
        verify(tokenStore).evictPermissions(50L);
        verify(tokenStore).bumpAuthVersion(50L);
        verify(tokenStore).evictPermissions(51L);
        verify(tokenStore).bumpAuthVersion(51L);
    }

    @Test
    void delete_重复删除幂等返回不存在() {
        setCaller(1, true);
        SysMenu deleted = menu(10L, 0L, 2, 0, 1, 0);
        deleted.setDeleted(1);
        when(menuMapper.selectById(10L)).thenReturn(deleted);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(10L));
        assertEquals("MENU_NOT_FOUND", ex.code());
    }

    /* ========================= changeStatus ========================= */

    @Test
    void changeStatus_非超级管理员不可操作系统菜单() {
        setCaller(2, false, "system:menu:update");
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, 2, 0, 1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(1L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void changeStatus_停用启用失效受影响用户() {
        setCaller(1, true);
        when(menuMapper.selectById(10L)).thenReturn(menu(10L, 0L, 2, 0, 1, 0));
        when(menuMapper.selectUserIdsByMenuId(10L)).thenReturn(Set.of(60L));

        menuService.changeStatus(10L, new ChangeMenuStatusRequest(0));

        verify(menuMapper).updateById(any(SysMenu.class));
        verify(tokenStore).evictPermissions(60L);
        verify(tokenStore).bumpAuthVersion(60L);
    }

    @Test
    void changeStatus_停用有启用子节点的菜单被拒绝() {
        // 目录下有启用的子节点时，停用父节点会把子节点提升为孤儿根，应拒绝。
        setCaller(1, true);
        when(menuMapper.selectById(10L)).thenReturn(menu(10L, 0L, 2, 0, 1, 0));
        when(menuMapper.countEnabledChildren(10L)).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(10L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_HAS_ENABLED_CHILDREN", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void changeStatus_启用父已停用的子节点被拒绝() {
        // 操作顺序：停用子节点 → 停用父节点 → 重新启用子节点。
        // 父节点已停用时，子节点不得启用，否则会成为可见的孤儿根。
        setCaller(1, true);
        // 子节点 11 的父节点是 10（已停用）
        SysMenu child = menu(11L, 10L, 2, 0, 1, 0);
        child.setStatus(0); // 子节点当前停用
        SysMenu disabledParent = menu(10L, 0L, 2, 0, 1, 0);
        disabledParent.setStatus(0); // 父节点已停用
        when(menuMapper.selectById(11L)).thenReturn(child);
        when(menuMapper.selectById(10L)).thenReturn(disabledParent);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(11L, new ChangeMenuStatusRequest(1)));
        assertEquals("MENU_PARENT_DISABLED", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void changeStatus_未关联目标菜单角色被拒绝() {
        // 调用者（userId=2）没有任何角色关联到目标菜单（countUserMenuRoleLinks=0），
        // 即使目标菜单 permission 在调用者权限集中，也应被拒——归属现由持久化角色关联决定。
        setCaller(2, false, "system:menu:update");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("system:user:delete");
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(5L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void delete_资源级未持有目标当前权限被拒绝() {
        // 调用者只有 system:menu:delete，但目标菜单当前 permission 为 system:user:delete（调用者没有）。
        setCaller(2, false, "system:menu:delete");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("system:user:delete");
        when(menuMapper.selectById(5L)).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(5L));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).deleteById(any(Long.class));
    }

    /* ========================= 空 permission 结构节点的资源级保护（子树校验） ========================= */

    @Test
    void update_空权限结构节点_未持有子树权限被拒绝() {
        // 目录节点（空 permission）下挂 knowledge:* 按钮；调用者仅持有 system:menu:*，
        // 未持有子树中的 knowledge:base:list 等权限 → 资源级校验应拒绝。
        setCaller(2, false, "system:menu:update");
        SysMenu dir = menu(50L, 0L, 2, 0, 1, 0); // 空 permission 的目录
        when(menuMapper.selectById(50L)).thenReturn(dir);
        // 子树按钮：menu_id=51, permission=knowledge:base:list
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(50L, updateReq(0L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void update_空权限结构节点_持有子树全部权限允许() {
        // 调用者持有子树全部权限（knowledge:base:list）→ 允许修改空 permission 的目录。
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu dir = menu(50L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(50L)).thenReturn(dir);
        when(ownerRoleMapper.countOwnerLinks(2L, 50L)).thenReturn(1); // 调用者是目标菜单 owner
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));
        when(menuMapper.selectUserIdsByMenuId(50L)).thenReturn(Set.of());

        menuService.update(50L, updateReq(0L, "改名", 2, "Knowledge", "/knowledge", ""));

        verify(menuMapper).updateById(any(SysMenu.class));
    }

    @Test
    void delete_空权限结构节点_未持有子树权限被拒绝() {
        // 删除空 permission 的目录，但调用者未持有子树权限 → 拒绝。
        setCaller(2, false, "system:menu:delete");
        SysMenu dir = menu(50L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(50L)).thenReturn(dir);
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(50L));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void changeStatus_空权限结构节点_未持有子树权限被拒绝() {
        // 停用空 permission 的目录，但调用者未持有子树权限 → 拒绝。
        setCaller(2, false, "system:menu:update");
        SysMenu dir = menu(50L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(50L)).thenReturn(dir);
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(50L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /* ========================= [P0-1] 目标父目录资源级授权回归 ========================= */

    @Test
    void create_无权在业务根目录下创建节点被拒绝() {
        // 调用者仅持有 system:menu:*，未持有 knowledge:* → 不得在 Knowledge 根目录下创建节点。
        setCaller(2, false, "system:menu:create", "system:menu:list");
        // Knowledge 根目录（空 permission）
        SysMenu knowledgeDir = menu(50L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(50L)).thenReturn(knowledgeDir);
        // 子树按钮决定归属：knowledge:base:list
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(50L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void update_不能把节点移动到无权管理的业务目录被拒绝() {
        // 调用者持有 system:menu:update，且持有被移动节点（menuId=5）的当前 permission；
        // 但无权写入目标 Ingest 根目录（未持有 ingest:task:list）→ 拒绝移动。
        setCaller(2, false, "system:menu:update", "system:audit:list");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("system:audit:list"); // 调用者持有，assertHoldsMenuPermission 通过
        when(menuMapper.selectById(5L)).thenReturn(existing);
        // 目标 Ingest 根目录（空 permission），子树按钮 ingest:task:list
        SysMenu ingestDir = menu(60L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(60L)).thenReturn(ingestDir);
        when(menuMapper.selectChildIds(60L)).thenReturn(Set.of(61L));
        when(menuMapper.selectChildIds(61L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(61L)))
                .thenReturn(Set.of("ingest:task:list"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(60L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /* ========================= [P0-2] 停用后可经角色关联重新启用 ========================= */

    @Test
    void changeStatus_停用后经角色关联仍可重新启用() {
        // 模拟：管理员已停用自己负责的唯一权限菜单，Token 失效后其有效权限集不再包含该 permission。
        // 但调用者角色仍关联该菜单（countUserMenuRoleLinks=1）→ 应允许重新启用，避免自锁。
        // 调用者权限集中故意不含 knowledge:base:list（模拟停用后刷新 Token 的状态）。
        setCaller(2, false, "system:menu:update");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("knowledge:base:list");
        existing.setStatus(0); // 已停用
        when(menuMapper.selectById(5L)).thenReturn(existing);
        // 角色仍关联该菜单 → 归属校验通过
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1);
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        menuService.changeStatus(5L, new ChangeMenuStatusRequest(1));

        verify(menuMapper).updateById(argThat((SysMenu m) ->
                m.getStatus() != null && m.getStatus() == 1));
    }

    /* ========================= [P1-1] 停用后代权限仍参与归属校验 ========================= */

    @Test
    void update_空权限目录的停用后代权限仍参与归属校验() {
        // Knowledge 根目录（空 permission）的子树按钮已停用（status=0）。
        // 若归属校验仅取 status=1 后代，则会漏掉 knowledge:base:list，错误地允许
        // 仅持有 system:menu:* 的调用者修改该目录。使用 IgnoreStatus 查询后应拒绝。
        setCaller(2, false, "system:menu:update");
        SysMenu dir = menu(50L, 0L, 2, 0, 1, 0); // 空 permission 目录
        when(menuMapper.selectById(50L)).thenReturn(dir);
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        // 关键：后代按钮已停用，但 IgnoreStatus 查询仍返回其 permission
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(50L, updateReq(0L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void changeStatus_已是目标状态被拒绝() {
        setCaller(1, true);
        SysMenu disabled = menu(10L, 0L, 2, 0, 1, 0);
        disabled.setStatus(0);
        when(menuMapper.selectById(10L)).thenReturn(disabled);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(10L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_ALREADY_DISABLED", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));

        SysMenu enabled = menu(11L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(11L)).thenReturn(enabled);
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(11L, new ChangeMenuStatusRequest(1)));
        assertEquals("MENU_ALREADY_ENABLED", ex2.code());
    }

    /* ========================= [P0] 空子树（无自身 permission、无后代）回归 ========================= */

    @Test
    void update_空权限无后代目录_无角色关联者不能修改() {
        // 空 permission、无后代 → 边界为空，归属改由角色关联判定；无关联则拒绝（不能放行）。
        setCaller(2, false, "system:menu:update");
        SysMenu emptyDir = menu(90L, 0L, 2, 0, 1, 0); // 空 permission，无后代
        when(menuMapper.selectById(90L)).thenReturn(emptyDir);
        when(menuMapper.selectChildIds(90L)).thenReturn(Set.of());
        when(ownerRoleMapper.countOwnerLinks(2L, 90L)).thenReturn(0); // 无角色关联

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(90L, updateReq(0L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void delete_空权限无后代目录_无角色关联者不能删除() {
        // 删除空 permission、无后代目录，但调用者无角色关联 → 拒绝。
        setCaller(2, false, "system:menu:delete");
        SysMenu emptyDir = menu(90L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(90L)).thenReturn(emptyDir);
        when(menuMapper.selectChildIds(90L)).thenReturn(Set.of());
        when(menuMapper.countChildren(90L)).thenReturn(0);
        when(ownerRoleMapper.countOwnerLinks(2L, 90L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(90L));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void create_在空权限无后代目录下创建_无角色关联者被拒绝() {
        // 在空 permission、无后代的目录下创建子节点，但调用者无角色关联 → 拒绝。
        setCaller(2, false, "system:menu:create");
        SysMenu emptyDir = menu(90L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(90L)).thenReturn(emptyDir);
        when(menuMapper.selectChildIds(90L)).thenReturn(Set.of());
        when(ownerRoleMapper.countOwnerLinks(2L, 90L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(90L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    /* ========================= [P1-1] 已停用/已删除角色不视为所有者 ========================= */

    @Test
    void changeStatus_仅通过已停用角色关联仍被拒绝() {
        // 用户历史上借由已停用/已删除角色关联过菜单，但当前无有效角色关联 → 不得启停。
        // countUserMenuRoleLinks 内连接有效角色后返回 0。
        setCaller(2, false, "system:menu:update");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setStatus(0); // 已停用
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(0); // 无有效角色关联

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(5L, new ChangeMenuStatusRequest(1)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /* ========================= [P1-2] 目标自身 permission 非空时仍校验全部后代 ========================= */

    @Test
    void update_仅有父节点权限无后代权限被拒绝() {
        // AiChat 根菜单具有 ai:chat:list，子节点具有 ai:chat:query。
        // 调用者仅有 ai:chat:list → 边界 = {ai:chat:list, ai:chat:query}，缺后者 → 拒绝。
        setCaller(2, false, "system:menu:update", "ai:chat:list");
        SysMenu aiChat = menu(80L, 0L, 2, 0, 1, 0);
        aiChat.setPermission("ai:chat:list");
        when(menuMapper.selectById(80L)).thenReturn(aiChat);
        when(menuMapper.selectChildIds(80L)).thenReturn(Set.of(81L));
        when(menuMapper.selectChildIds(81L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(81L)))
                .thenReturn(Set.of("ai:chat:query"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(80L, updateReq(0L, "AiChat", 2, "AiChat", "/aichat", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void update_拥有父节点及全部后代权限允许() {
        // 调用者同时持有 ai:chat:list 与 ai:chat:query → 边界全部满足，允许修改。
        setCaller(2, false, "system:menu:update", "ai:chat:list", "ai:chat:query");
        SysMenu aiChat = menu(80L, 0L, 2, 0, 1, 0);
        aiChat.setPermission("ai:chat:list");
        when(menuMapper.selectById(80L)).thenReturn(aiChat);
        when(ownerRoleMapper.countOwnerLinks(2L, 80L)).thenReturn(1); // 调用者是目标菜单 owner
        when(menuMapper.selectChildIds(80L)).thenReturn(Set.of(81L));
        when(menuMapper.selectChildIds(81L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(81L)))
                .thenReturn(Set.of("ai:chat:query"));
        when(menuMapper.selectUserIdsByMenuId(80L)).thenReturn(Set.of());

        menuService.update(80L, updateReq(0L, "AiChat", 2, "AiChat", "/aichat", ""));

        verify(menuMapper).updateById(any(SysMenu.class));
    }

    /* ========================= [P0] 空结构节点创建时继承父角色关联（防自锁） ========================= */

    @Test
    void create_非管理员空结构节点继承父角色关联_完整生命周期() {
        // 非管理员（userId=2）通过角色关联拥有父目录（空 permission、无后代 → 边界为空，归属由角色关联决定）。
        // 在其下创建空 permission 目录 → 应继承父目录中调用者持有的角色关联，建立持久化归属，
        // 从而后续修改该目录、以及在该目录下创建子节点，均能成功（不再自锁）。
        setCaller(2, false, "system:menu:create", "system:menu:update");
        SysMenu parent = menu(40L, 0L, 2, 0, 1, 0); // 空 permission 目录，无后代
        when(menuMapper.selectById(40L)).thenReturn(parent);
        when(menuMapper.selectChildIds(40L)).thenReturn(Set.of());
        // 父目录归属：调用者通过角色 100 关联到父目录（空边界 → 角色关联判定）
        when(ownerRoleMapper.countOwnerLinks(2L, 40L)).thenReturn(1);
        // 继承：父目录中调用者持有的有效角色 = {100}；另有角色 200 关联到父目录但调用者不持有 → 不应继承
        when(ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(2L, 40L)).thenReturn(Set.of(100L));
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(41L);
            return 1;
        });

        // 1) 在父目录下创建空 permission 目录
        Long newId = menuService.create(createReq(40L, "新目录", 2, "NewDir", "/newdir", ""));
        assertEquals(41L, newId);
        // 验证：继承的所有者角色关联已建立（仅调用者持有的角色 100，未扩大到角色 200）。
        // 写入独立的归属表 sys_menu_owner_role，而非 sys_role_menu——避免权限扩散。
        ArgumentCaptor<SysMenuOwnerRole> ownerCaptor = ArgumentCaptor.forClass(SysMenuOwnerRole.class);
        verify(ownerRoleMapper).insert(ownerCaptor.capture());
        assertEquals(100L, ownerCaptor.getValue().getRoleId());
        assertEquals(41L, ownerCaptor.getValue().getMenuId());
        verify(ownerRoleMapper, never()).insert(argThat((SysMenuOwnerRole mor) ->
                mor.getRoleId() != null && mor.getRoleId().equals(200L)));
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));

        // 2) 修改新目录 —— 模拟继承的所有者关联已持久化（countOwnerLinks=1）
        SysMenu newDir = menu(41L, 40L, 2, 0, 1, 0);
        when(menuMapper.selectById(41L)).thenReturn(newDir);
        when(menuMapper.selectChildIds(41L)).thenReturn(Set.of());
        when(ownerRoleMapper.countOwnerLinks(2L, 41L)).thenReturn(1); // 继承后调用者关联到新目录
        when(menuMapper.selectUserIdsByMenuId(41L)).thenReturn(Set.of());

        menuService.update(41L, updateReq(40L, "改名", 2, "NewDir", "/newdir", ""));
        verify(menuMapper).updateById(any(SysMenu.class));

        // 3) 在新目录下创建子节点 —— 继承新目录的所有者角色关联
        when(menuMapper.selectById(41L)).thenReturn(newDir); // validateParent 加载父
        when(ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(2L, 41L)).thenReturn(Set.of(100L));
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(42L);
            return 1;
        });

        Long childId = menuService.create(createReq(41L, "子目录", 2, "Child", "/child", ""));
        assertEquals(42L, childId);
        verify(ownerRoleMapper).insert(argThat((SysMenuOwnerRole mor) ->
                mor.getRoleId().equals(100L) && mor.getMenuId().equals(42L)));
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));
    }

    @Test
    void create_非管理员创建顶级空结构节点被拒绝() {
        // 顶级空 permission 节点无父可继承，非超级管理员不得创建（仅超级管理员可创建），
        // 避免产生无法确定归属的孤儿根。
        setCaller(2, false, "system:menu:create");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(0L, "顶级目录", 2, "Top", "/top", "")));
        assertEquals("MENU_OWNERSHIP_REQUIRED", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void create_父目录无可继承角色关联被拒绝() {
        // 父目录边界非空（含子树 permission），调用者通过权限持有父目录 → assertCanWriteUnder 通过；
        // 但父目录中调用者无任何角色关联（角色关联在其子节点上）→ 无法为新节点确定持久化归属，应拒绝。
        setCaller(2, false, "system:menu:create", "knowledge:base:list");
        SysMenu parent = menu(50L, 0L, 2, 0, 1, 0); // 空 permission 目录
        when(menuMapper.selectById(50L)).thenReturn(parent);
        when(ownerRoleMapper.countOwnerLinks(2L, 50L)).thenReturn(1); // 调用者是父目录 owner（先通过归属主校验）
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list"));
        // 父目录中调用者持有的有效角色关联为空 → 无法继承
        when(ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(2L, 50L)).thenReturn(Set.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(50L, "新目录", 2, "NewDir", "/newdir", "")));
        assertEquals("MENU_OWNERSHIP_REQUIRED", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
        verify(ownerRoleMapper, never()).insert(any(SysMenuOwnerRole.class));
    }

    @Test
    void create_非空permission子菜单_继承归属后可停用并重新启用() {
        // 非空 permission 子菜单同样继承父目录的角色关联 → changeStatus 不再自锁。
        setCaller(2, false, "system:menu:create", "system:menu:update", "biz:x:list");
        SysMenu parent = menu(40L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(40L)).thenReturn(parent);
        when(menuMapper.selectChildIds(40L)).thenReturn(Set.of());
        when(ownerRoleMapper.countOwnerLinks(2L, 40L)).thenReturn(1);
        when(ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(2L, 40L)).thenReturn(Set.of(100L));
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(41L);
            return 1;
        });

        // 创建非空 permission 子菜单
        Long childId = menuService.create(createReq(40L, "子菜单", 1, "Child", "/child", "biz:x:list"));
        assertEquals(41L, childId);
        // 继承的所有者关联写入归属表，不得写入 sys_role_menu（否则继承角色 A 的所有成员
        // 都会被动获得 biz:x:list，造成权限扩散）
        verify(ownerRoleMapper).insert(argThat((SysMenuOwnerRole mor) ->
                mor.getRoleId().equals(100L) && mor.getMenuId().equals(41L)));
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));

        // 停用：继承的所有者关联使 countOwnerLinks(2, 41) > 0 → 不再自锁
        SysMenu child = menu(41L, 40L, 1, 0, 1, 0);
        child.setPermission("biz:x:list");
        when(menuMapper.selectById(41L)).thenReturn(child);
        when(ownerRoleMapper.countOwnerLinks(2L, 41L)).thenReturn(1); // 继承后关联存在
        when(menuMapper.countEnabledChildren(41L)).thenReturn(0);
        when(menuMapper.selectUserIdsByMenuId(41L)).thenReturn(Set.of());

        menuService.changeStatus(41L, new ChangeMenuStatusRequest(0));

        // 重新启用（changeStatus 直接修改传入对象，Mockito 记录的是同一引用，故只校验总调用次数）
        menuService.changeStatus(41L, new ChangeMenuStatusRequest(1));
        verify(menuMapper, times(2)).updateById(any(SysMenu.class));
    }

    @Test
    void changeStatus_未继承关联的其他角色_即使拥有相同permission也不能启停() {
        // 菜单由 userId=2 通过继承的角色关联持有；userId=3 拥有相同 permission 但未继承该关联 → 拒绝。
        setCaller(3, false, "system:menu:update", "biz:x:list"); // 与创建者相同的接口/菜单权限
        SysMenu child = menu(41L, 40L, 1, 0, 1, 0);
        child.setPermission("biz:x:list");
        when(menuMapper.selectById(41L)).thenReturn(child);
        // userId=3 未继承到该菜单的角色关联 → countUserMenuRoleLinks = 0
        when(ownerRoleMapper.countOwnerLinks(3L, 41L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(41L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /* ========================= [P0] 资源归属与权限授权解耦：继承归属不得扩散 permission ========================= */

    /**
     * 核心权限扩散回归测试。
     *
     * <p>场景：用户 U1 同时持有角色 A 与角色 B，permission knowledge:x 仅来自角色 B；
     * 父目录的所有者角色为 A（与 B 无关）。U1 在父目录下创建 knowledge:x 菜单。
     * <ul>
     *   <li>正确行为：仅建立 owner(A, menu)，不建立 role_menu(A, menu)——
     *       否则角色 A 的所有成员都会被动获得 knowledge:x。</li>
     *   <li>仅持有角色 A 的 U2 不得因此获得 knowledge:x（T2）。</li>
     *   <li>U1 仍能通过归属关系启停新菜单（T3）。</li>
     * </ul>
     */
    @Test
    void create_继承归属只写归属表_不写sys_role_menu_避免权限扩散() {
        // U1（userId=2）：持有角色 A（owner 父目录）+ 角色 B（拥有 knowledge:x）。
        // assertPermissionWritable 校验调用者聚合后拥有 knowledge:x → 通过。
        setCaller(2, false, "system:menu:create", "knowledge:x:list");
        SysMenu parent = menu(40L, 0L, 2, 0, 1, 0); // 空 permission 目录
        when(menuMapper.selectById(40L)).thenReturn(parent);
        when(menuMapper.selectChildIds(40L)).thenReturn(Set.of());
        // 父目录边界为空 → 归属由所有者角色关联判定；调用者通过角色 A(100) 持有父目录
        when(ownerRoleMapper.countOwnerLinks(2L, 40L)).thenReturn(1);
        // 继承：父目录中调用者持有的有效所有者角色 = {A=100}
        when(ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(2L, 40L)).thenReturn(Set.of(100L));
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(41L);
            return 1;
        });

        Long childId = menuService.create(createReq(40L, "知识菜单", 1, "KnowledgeX", "/kx", "knowledge:x:list"));
        assertEquals(41L, childId);

        // 正确：所有者关联写入归属表（角色 A → 新菜单）
        verify(ownerRoleMapper).insert(argThat((SysMenuOwnerRole mor) ->
                mor.getRoleId().equals(100L) && mor.getMenuId().equals(41L)));
        // 关键防扩散：不得写入 sys_role_menu——否则角色 A 的所有成员（包括没有 knowledge:x 的 U2）
        // 都会被动获得 knowledge:x:list
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));
    }

    /**
     * T3：创建者 U1 仍能通过继承的所有者关联启停新菜单（归属表有 A → menu 的链接）。
     */
    @Test
    void changeStatus_创建者通过继承的所有者关联仍可启停() {
        setCaller(2, false, "system:menu:update", "knowledge:x:list");
        SysMenu child = menu(41L, 40L, 1, 0, 1, 0);
        child.setPermission("knowledge:x:list");
        when(menuMapper.selectById(41L)).thenReturn(child);
        // 创建者通过继承的所有者角色 A(100) 关联到该菜单
        when(ownerRoleMapper.countOwnerLinks(2L, 41L)).thenReturn(1);
        when(menuMapper.countEnabledChildren(41L)).thenReturn(0);
        when(menuMapper.selectUserIdsByMenuId(41L)).thenReturn(Set.of());

        menuService.changeStatus(41L, new ChangeMenuStatusRequest(0));

        verify(menuMapper).updateById(argThat((SysMenu m) ->
                m.getStatus() != null && m.getStatus() == 0));
    }

    /**
     * T4（修正）：仅持有角色 C 的 U3，即使拥有相同的 knowledge:x permission，
     * 但 C 不是该菜单的所有者角色 → 启停应被拒。
     *
     * <p>注意：角色归属语义下，所有者角色的<b>成员</b>都是所有者（故 U2 持角色 A 可启停）；
     * 此处 U3 持有的是另一个同样拥有该 permission 的角色 C，但没有 owner 关联 → 拒绝。
     */
    @Test
    void changeStatus_非所有者角色_即使拥有相同permission也不能启停() {
        // U3（userId=3）：持有角色 C，拥有 knowledge:x，但 C 不是该菜单的所有者
        setCaller(3, false, "system:menu:update", "knowledge:x:list");
        SysMenu child = menu(41L, 40L, 1, 0, 1, 0);
        child.setPermission("knowledge:x:list");
        when(menuMapper.selectById(41L)).thenReturn(child);
        // 角色 C 未关联到该菜单的归属 → countOwnerLinks = 0
        when(ownerRoleMapper.countOwnerLinks(3L, 41L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(41L, new ChangeMenuStatusRequest(0)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /**
     * T5：停用所有者角色 A 后，U1 不再能通过 A 管理菜单 → 启停应被拒。
     *
     * <p>countOwnerLinks 内连接有效角色（status=1、deleted=0），已停用角色被过滤 → 链接数归零。
     */
    @Test
    void changeStatus_所有者角色已停用后_成员无法再启停() {
        setCaller(2, false, "system:menu:update", "knowledge:x:list");
        SysMenu child = menu(41L, 40L, 1, 0, 1, 0);
        child.setPermission("knowledge:x:list");
        when(menuMapper.selectById(41L)).thenReturn(child);
        // 所有者角色 A 已停用 → countOwnerLinks 内连接有效角色后返回 0
        when(ownerRoleMapper.countOwnerLinks(2L, 41L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.changeStatus(41L, new ChangeMenuStatusRequest(1)));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /**
     * T6：删除菜单时，其所有者归属关系必须被同步清理，不得残留孤儿行。
     */
    @Test
    void delete_同步清理该菜单的所有者归属关系() {
        setCaller(1, true); // 超级管理员删除
        when(menuMapper.selectById(41L)).thenReturn(menu(41L, 40L, 1, 0, 1, 0));
        when(menuMapper.countChildren(41L)).thenReturn(0);
        when(menuMapper.selectUserIdsByMenuId(41L)).thenReturn(Set.of());

        menuService.delete(41L);

        verify(menuMapper).deleteById(41L);
        // 必须同步清理归属表，否则残留 (menuId=41, roleId=*) 孤儿行
        verify(ownerRoleMapper).delete(any());
    }

    /* ========================= [P0] 统一授权：owner 是所有写入口的主校验 ========================= */

    /**
     * R1：非 owner 即使持有与目标菜单相同的 permission，也不能修改。
     *
     * <p>owner 校验是所有写入口的主校验、先于 permission 边界校验执行。
     * 此用例证明 permission 持有不能替代资源归属——消除"同 permission 即可越权改动"的 P0。
     */
    @Test
    void update_非owner_即使持有相同permission_也不能修改() {
        // 调用者持有 knowledge:base:list，目标菜单同样是 knowledge:base:list → 权限边界校验本就通过；
        // 但调用者不是该菜单的 owner → 修改应被拒（主校验在边界校验之前拦截）。
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("knowledge:base:list"); // 调用者同样持有
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(0); // 非 owner

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(0L, "X", 2, "X", "/x", "knowledge:base:list")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /**
     * R2：非 owner 即使持有与目标菜单相同的 permission，也不能删除。
     */
    @Test
    void delete_非owner_即使持有相同permission_也不能删除() {
        setCaller(2, false, "system:menu:delete", "knowledge:base:list");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("knowledge:base:list"); // 调用者同样持有
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(menuMapper.countChildren(5L)).thenReturn(0);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(0); // 非 owner

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.delete(5L));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).deleteById(any(Long.class));
    }

    /**
     * R3：非 owner 即使持有父目录子树的全部 permission，也不能在父目录下创建子节点。
     *
     * <p>assertCanWriteUnder 现在以 owner 为主校验、permission 边界为纵深防御。
     */
    @Test
    void create_非owner_即使持有父目录全部permission_也不能创建子节点() {
        setCaller(2, false, "system:menu:create", "knowledge:base:list");
        SysMenu parent = menu(50L, 0L, 2, 0, 1, 0); // 空 permission 目录
        when(menuMapper.selectById(50L)).thenReturn(parent);
        when(menuMapper.selectChildIds(50L)).thenReturn(Set.of(51L));
        when(menuMapper.selectChildIds(51L)).thenReturn(Set.of());
        when(menuMapper.selectPermissionsByMenuIdsIgnoreStatus(Set.of(51L)))
                .thenReturn(Set.of("knowledge:base:list")); // 调用者持有
        when(ownerRoleMapper.countOwnerLinks(2L, 50L)).thenReturn(0); // 非 owner

        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.create(createReq(50L, "X", 2, "X", "/x", "")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    /**
     * R5（delete 部分）：owner 角色的成员可以删除自己负责的菜单。
     *
     * <p>与"继承父角色关联"生命周期测试互补，聚焦 delete 入口的 owner 主校验。
     */
    @Test
    void delete_所有者角色成员可以删除菜单() {
        setCaller(2, false, "system:menu:delete", "knowledge:base:list");
        SysMenu target = menu(5L, 0L, 2, 0, 1, 0);
        target.setPermission("knowledge:base:list"); // 调用者持有
        when(menuMapper.selectById(5L)).thenReturn(target);
        when(menuMapper.countChildren(5L)).thenReturn(0);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // owner
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        menuService.delete(5L);

        verify(menuMapper).deleteById(5L);
        // 删除同步清理归属关系
        verify(ownerRoleMapper).delete(any());
    }

    /**
     * R6a：调用者是源菜单的 owner，但不是目标父目录的 owner → 移动应被拒。
     *
     * <p>移动操作在 parentId 真正变化时才触发目标父目录的 owner 校验。
     */
    @Test
    void update_源owner但非目标父owner_移动被拒绝() {
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("knowledge:base:list"); // 调用者持有，边界校验通过
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // 是源菜单 owner
        when(menuMapper.selectChildIds(5L)).thenReturn(Set.of());
        // 目标父目录（空 permission，无后代），调用者不是其 owner
        SysMenu targetParent = menu(60L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(60L)).thenReturn(targetParent);
        when(menuMapper.selectChildIds(60L)).thenReturn(Set.of());
        // countOwnerLinks(2, 60) 未桩 → 默认 0

        // parentId 从 0 改为 60（真正移动）→ assertCanWriteUnder(2, 60) → owner 校验失败
        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(60L, "X", 2, "X", "/x", "knowledge:base:list")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /**
     * R6b：调用者同时是源菜单和目标父目录的 owner → 移动成功，原 owner 归属保持不变（不自动转让）。
     */
    @Test
    void update_源与目标父均为owner_移动成功且原owner保留() {
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("knowledge:base:list");
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // 源 owner
        when(menuMapper.selectChildIds(5L)).thenReturn(Set.of());
        SysMenu targetParent = menu(60L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(60L)).thenReturn(targetParent);
        when(menuMapper.selectChildIds(60L)).thenReturn(Set.of());
        when(ownerRoleMapper.countOwnerLinks(2L, 60L)).thenReturn(1); // 目标父 owner
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        menuService.update(5L, updateReq(60L, "X", 2, "X", "/x", "knowledge:base:list"));

        verify(menuMapper).updateById(any(SysMenu.class));
        // 移动不改变原 owner 归属（不自动转让）：不写入、不删除 owner 行
        verify(ownerRoleMapper, never()).insert(any(SysMenuOwnerRole.class));
        verify(ownerRoleMapper, never()).delete(any());
    }

    /**
     * R7：owner 校验通过，但 assertPermissionWritable 仍是纵深防御——
     * 不能把菜单改成调用者自己没有的 permission。
     */
    @Test
    void update_owner_但新permission超出自身权限仍被assertPermissionWritable拦截() {
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu existing = menu(5L, 0L, 2, 0, 1, 0);
        existing.setPermission("knowledge:base:list"); // 调用者持有
        when(menuMapper.selectById(5L)).thenReturn(existing);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // owner
        when(menuMapper.selectChildIds(5L)).thenReturn(Set.of());
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        // 尝试改成调用者没有的 permission
        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(0L, "X", 2, "X", "/x", "system:user:delete")));
        assertEquals("PERMISSION_NOT_SUBSET", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    /* ========================= 兼容性：拥有子菜单但不拥有父目录的历史升级数据 ========================= */

    /**
     * Comp1：角色只拥有子菜单的 owner 关联、未关联父目录时，不移动节点的普通更新仍成功。
     *
     * <p>历史升级数据中，V10 回填把历史 role_menu 推定为 owner，可能只覆盖到部分层级。
     * 只要不移动节点（parentId 未变化），就不应要求父目录 owner——避免错误拒绝合法改名。
     */
    @Test
    void update_拥有子菜单但不拥有父目录_未移动节点的普通更新仍成功() {
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu child = menu(5L, 10L, 2, 0, 1, 0); // 子菜单，parentId=10
        child.setPermission("knowledge:base:list"); // 调用者持有
        when(menuMapper.selectById(5L)).thenReturn(child);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // 是子菜单 owner
        // 父目录存在且启用，但调用者不是其 owner → 不移动时不校验父目录归属
        SysMenu parent = menu(10L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(10L)).thenReturn(parent);
        when(menuMapper.selectChildIds(5L)).thenReturn(Set.of());
        when(menuMapper.selectUserIdsByMenuId(5L)).thenReturn(Set.of());

        // parentId 保持 10（不变），仅改名 → 应成功
        menuService.update(5L, updateReq(10L, "改名", 2, "Knowledge", "/knowledge", "knowledge:base:list"));

        verify(menuMapper).updateById(any(SysMenu.class));
    }

    /**
     * Comp2：同样身份（拥有子菜单 owner、不拥有父目录 owner）尝试把子菜单移动到一个
     * 同样无归属的父目录时，移动应被拒。
     */
    @Test
    void update_拥有子菜单但不拥有目标父目录_移动被拒绝() {
        setCaller(2, false, "system:menu:update", "knowledge:base:list");
        SysMenu child = menu(5L, 10L, 2, 0, 1, 0); // 子菜单，原 parentId=10
        child.setPermission("knowledge:base:list");
        when(menuMapper.selectById(5L)).thenReturn(child);
        when(ownerRoleMapper.countOwnerLinks(2L, 5L)).thenReturn(1); // 是子菜单 owner
        when(menuMapper.selectChildIds(5L)).thenReturn(Set.of());
        // 目标父目录（parentId=20），调用者不是其 owner
        SysMenu targetParent = menu(20L, 0L, 2, 0, 1, 0);
        when(menuMapper.selectById(20L)).thenReturn(targetParent);
        when(menuMapper.selectChildIds(20L)).thenReturn(Set.of());

        // parentId 从 10 改为 20（真正移动）→ assertCanWriteUnder(2, 20) → owner 校验失败
        BusinessException ex = assertThrows(BusinessException.class,
                () -> menuService.update(5L, updateReq(20L, "X", 2, "X", "/x", "knowledge:base:list")));
        assertEquals("MENU_NOT_FOUND", ex.code());
        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }
}