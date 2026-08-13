package com.docbase.iam.role;

import com.docbase.common.core.BusinessException;
import com.docbase.iam.menu.OwnerLifecycleLockHook;
import com.docbase.iam.menu.mapper.MenuOwnerMutexMapper;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.menu.mapper.SysMenuOwnerRoleMapper;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.dto.AssignRoleMenusRequest;
import com.docbase.iam.role.dto.ChangeRoleStatusRequest;
import com.docbase.iam.role.dto.CreateRoleRequest;
import com.docbase.iam.role.dto.UpdateRoleRequest;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 角色管理服务层安全测试 —— 聚焦资源级授权、系统角色保护、菜单校验与权限子集。
 *
 * 采用纯 Mockito（不启动 Spring 上下文），通过手动设置 SecurityContext 模拟调用者身份。
 */
class RoleServiceTest {

    private SysRoleMapper roleMapper;
    private SysRoleMenuMapper roleMenuMapper;
    private SysMenuOwnerRoleMapper ownerRoleMapper;
    private MenuOwnerMutexMapper ownerMutexMapper;
    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SysMenuMapper menuMapper;
    private TokenStore tokenStore;
    private RoleService roleService;

    @BeforeEach
    void setUp() {
        roleMapper = mock(SysRoleMapper.class);
        roleMenuMapper = mock(SysRoleMenuMapper.class);
        ownerRoleMapper = mock(SysMenuOwnerRoleMapper.class);
        ownerMutexMapper = mock(MenuOwnerMutexMapper.class);
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        menuMapper = mock(SysMenuMapper.class);
        tokenStore = mock(TokenStore.class);
        // owner 生命周期互斥守卫默认存在（迁移已执行），避免各用例重复桩装。
        when(ownerMutexMapper.lockGuardRow()).thenReturn(1);
        roleService = new RoleService(roleMapper, roleMenuMapper, ownerRoleMapper,
                ownerMutexMapper, mock(OwnerLifecycleLockHook.class),
                userMapper, userRoleMapper, menuMapper, tokenStore);
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

    private SysRole role(long id, int isSystem) {
        SysRole r = new SysRole();
        r.setRoleId(id);
        r.setRoleName("role" + id);
        r.setRoleKey("role_key_" + id);
        r.setStatus(1);
        r.setIsSystem(isSystem);
        r.setDeleted(0);
        return r;
    }

    private CreateRoleRequest createReq(String key, List<Long> menuIds) {
        return new CreateRoleRequest("Role " + key, key, 1, 1, 1, null, menuIds);
    }

    private UpdateRoleRequest updateReq(String key, List<Long> menuIds) {
        return new UpdateRoleRequest("Role " + key, key, 1, 1, null, menuIds);
    }

    /** 桩装菜单校验：指定 menuId 集合全部"有效"，并返回给定的权限集合。 */
    private void stubValidMenus(List<Long> menuIds, String... permissions) {
        when(menuMapper.countValidMenus(menuIds)).thenReturn(menuIds.size());
        when(menuMapper.selectPermissionsByMenuIds(menuIds)).thenReturn(Set.of(permissions));
    }

    /* ========================= create ========================= */

    @Test
    void create_超级管理员可以创建角色并授权() {
        setCaller(1, true);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(10L);
            return 1;
        });
        stubValidMenus(List.of(100L), "system:menu:list");

        Long id = roleService.create(createReq("r1", List.of(100L)));

        assertEquals(10L, id);
        verify(roleMapper).insert(any(SysRole.class));
        verify(roleMenuMapper).insert(any(SysRoleMenu.class));
    }

    @Test
    void create_接口创建的角色isSystem强制为0() {
        setCaller(1, true);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(11L);
            return 1;
        });

        roleService.create(createReq("r2", null));

        verify(roleMapper).insert(argThat((SysRole r) -> r.getIsSystem() != null && r.getIsSystem() == 0));
    }

    @Test
    void create_重复roleKey被拒绝() {
        setCaller(1, true);
        when(roleMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.create(createReq("dup", null)));
        assertEquals("ROLE_KEY_EXISTS", ex.code());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void create_普通管理员不能授予自己没有的权限() {
        // 调用者只有 system:role:list，但试图授予 system:user:delete
        setCaller(2, false, "system:role:list");
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(12L);
            return 1;
        });
        stubValidMenus(List.of(200L), "system:user:delete");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.create(createReq("r3", List.of(200L))));
        assertEquals("PERMISSION_NOT_SUBSET", ex.code());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void create_普通管理员不能通过菜单取得admin_all() {
        setCaller(2, false, "system:role:list");
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(13L);
            return 1;
        });
        // 恶意菜单的 permission 字段被写成 admin:all
        stubValidMenus(List.of(300L), IamUserPrincipal.ADMIN_ALL_PERMISSION);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.create(createReq("r4", List.of(300L))));
        assertEquals("PERMISSION_NOT_GRANTABLE", ex.code());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void create_旧权限映射后不误判越权() {
        // 调用者 JWT 持有新格式 system:role:update；候选菜单的 permission 是旧格式 system:role:edit。
        // 归一化后两者一致，应允许创建（若未归一化会被误判为 PERMISSION_NOT_SUBSET）。
        setCaller(2, false, "system:role:update");
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(16L);
            return 1;
        });
        stubValidMenus(List.of(400L), "system:role:edit");

        Long id = roleService.create(createReq("r7", List.of(400L)));
        assertEquals(16L, id);
        verify(roleMapper).insert(any(SysRole.class));
    }

    @Test
    void create_引用不存在菜单被拒绝() {
        setCaller(1, true);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(14L);
            return 1;
        });
        // 3 个候选只有 2 个有效
        when(menuMapper.countValidMenus(List.of(1L, 2L, 3L))).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.create(createReq("r5", List.of(1L, 2L, 3L))));
        assertEquals("MENU_INVALID", ex.code());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void create_menuIds去重后授权() {
        setCaller(1, true);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysRole.class).setRoleId(15L);
            return 1;
        });
        stubValidMenus(List.of(100L), "system:menu:list");

        roleService.create(createReq("r6", List.of(100L, 100L, 100L)));

        // 去重后只插入一次
        verify(roleMenuMapper, times(1)).insert(any(SysRoleMenu.class));
    }

    /* ========================= update ========================= */

    @Test
    void update_普通管理员不能修改系统保留角色() {
        setCaller(2, false, "system:role:update");
        when(roleMapper.selectById(1L)).thenReturn(role(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.update(1L, updateReq("role_key_1", null)));
        // 为防信息泄露，统一返回 ROLE_NOT_FOUND
        assertEquals("ROLE_NOT_FOUND", ex.code());
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void update_超级管理员可以修改系统保留角色() {
        setCaller(1, true);
        SysRole sysRole = role(1, 1);
        when(roleMapper.selectById(1L)).thenReturn(sysRole);

        roleService.update(1L, updateReq("role_key_1", null));

        verify(roleMapper).updateById(any(SysRole.class));
    }

    @Test
    void update_修改roleKey冲突被拒绝() {
        setCaller(1, true);
        SysRole existing = role(1, 0);
        when(roleMapper.selectById(1L)).thenReturn(existing);
        // roleKey 未变时不查冲突；这里改成新 key，触发冲突
        when(roleMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.update(1L, updateReq("new_key", null)));
        assertEquals("ROLE_KEY_EXISTS", ex.code());
    }

    /* ========================= delete ========================= */

    @Test
    void delete_普通管理员不能删除系统保留角色() {
        setCaller(2, false, "system:role:delete");
        when(roleMapper.selectById(1L)).thenReturn(role(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.delete(1L));
        assertEquals("ROLE_NOT_FOUND", ex.code());
        verify(roleMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_先捕获受影响用户再删除关联() {
        setCaller(1, true);
        when(roleMapper.selectById(5L)).thenReturn(role(5, 0));
        // 两个用户拥有该角色
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                new SysUserRole(50L, 5L), new SysUserRole(51L, 5L)));

        roleService.delete(5L);

        verify(roleMapper).deleteById((Long) 5L);
        // 两个用户的权限缓存与 auth_version 都被失效
        verify(tokenStore).evictPermissions(50L);
        verify(tokenStore).bumpAuthVersion(50L);
        verify(tokenStore).evictPermissions(51L);
        verify(tokenStore).bumpAuthVersion(51L);
    }

    @Test
    void delete_删除角色时同步清理其菜单所有者归属() {
        setCaller(1, true);
        when(roleMapper.selectById(8L)).thenReturn(role(8, 0));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        roleService.delete(8L);

        verify(roleMapper).deleteById((Long) 8L);
        // 逻辑删除不会触发 ON DELETE CASCADE，必须显式清理该角色的菜单所有者归属。
        verify(ownerRoleMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    /* ========================= changeStatus ========================= */

    @Test
    void changeStatus_普通管理员不能停用系统保留角色() {
        setCaller(2, false, "system:role:update");
        when(roleMapper.selectById(1L)).thenReturn(role(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.changeStatus(1L, new ChangeRoleStatusRequest(0)));
        assertEquals("ROLE_NOT_FOUND", ex.code());
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void changeStatus_停用角色会失效受影响用户() {
        setCaller(1, true);
        when(roleMapper.selectById(6L)).thenReturn(role(6, 0));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new SysUserRole(60L, 6L)));

        roleService.changeStatus(6L, new ChangeRoleStatusRequest(0));

        verify(roleMapper).updateById(any(SysRole.class));
        verify(tokenStore).evictPermissions(60L);
        verify(tokenStore).bumpAuthVersion(60L);
    }

    /* ========================= 最后有效 owner 生命周期校验 ========================= */

    @Test
    void changeStatus_停用最后一个有效owner角色被拒绝() {
        setCaller(1, true);
        when(roleMapper.selectById(9L)).thenReturn(role(9, 0));
        // 角色 9 是某未删除菜单的唯一有效 owner
        when(ownerRoleMapper.selectMenusWhereRoleIsLastOwner(9L)).thenReturn(List.of(500L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.changeStatus(9L, new ChangeRoleStatusRequest(0)));
        assertEquals("ROLE_LAST_MENU_OWNER", ex.code());
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void changeStatus_存在其他有效owner时允许停用() {
        setCaller(1, true);
        when(roleMapper.selectById(10L)).thenReturn(role(10, 0));
        when(ownerRoleMapper.selectMenusWhereRoleIsLastOwner(10L)).thenReturn(List.of());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        roleService.changeStatus(10L, new ChangeRoleStatusRequest(0));

        verify(roleMapper).updateById(any(SysRole.class));
    }

    @Test
    void delete_删除最后一个有效owner角色被拒绝() {
        setCaller(1, true);
        when(roleMapper.selectById(11L)).thenReturn(role(11, 0));
        when(ownerRoleMapper.selectMenusWhereRoleIsLastOwner(11L)).thenReturn(List.of(501L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.delete(11L));
        assertEquals("ROLE_LAST_MENU_OWNER", ex.code());
        verify(roleMapper, never()).deleteById(any(Long.class));
        verify(ownerRoleMapper, never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void delete_存在其他有效owner时允许删除并清理归属() {
        setCaller(1, true);
        when(roleMapper.selectById(12L)).thenReturn(role(12, 0));
        when(ownerRoleMapper.selectMenusWhereRoleIsLastOwner(12L)).thenReturn(List.of());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        roleService.delete(12L);

        verify(roleMapper).deleteById((Long) 12L);
        verify(ownerRoleMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    /* ========================= assignMenus ========================= */

    @Test
    void assignMenus_普通管理员不能为系统保留角色授权() {
        setCaller(2, false, "system:role:update");
        when(roleMapper.selectById(1L)).thenReturn(role(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assignMenus(1L, new AssignRoleMenusRequest(List.of(100L))));
        assertEquals("ROLE_NOT_FOUND", ex.code());
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));
    }

    /* ========================= assertCanAssignRoles 角色分配授权 ========================= */

    @Test
    void assertCanAssignRoles_超级管理员可分配任意角色() {
        setCaller(1, true);
        SysRole r = role(1, 1);
        when(roleMapper.selectById(1L)).thenReturn(r);
        when(roleMenuMapper.selectMenuIdsByRoleId(1L)).thenReturn(Set.of(100L));
        when(menuMapper.selectPermissionsByMenuIds(Set.of(100L))).thenReturn(Set.of("system:menu:list"));

        // 超级管理员：系统保留角色 + 任意权限都允许
        assertDoesNotThrow(() -> roleService.assertCanAssignRoles(
                new IamUserPrincipal(1L, "admin", true, Set.of(IamUserPrincipal.ADMIN_ALL_PERMISSION)),
                List.of(1L)));
    }

    @Test
    void assertCanAssignRoles_角色不存在被拒绝() {
        setCaller(2, false, "system:role:list");
        when(roleMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(999L)));
        assertEquals("ROLE_NOT_FOUND", ex.code());
    }

    @Test
    void assertCanAssignRoles_已删除角色被拒绝() {
        setCaller(2, false, "system:role:list");
        SysRole deleted = role(7, 0);
        deleted.setDeleted(1);
        when(roleMapper.selectById(7L)).thenReturn(deleted);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(7L)));
        assertEquals("ROLE_NOT_FOUND", ex.code());
    }

    @Test
    void assertCanAssignRoles_停用角色被拒绝() {
        setCaller(2, false, "system:role:list");
        SysRole disabled = role(8, 0);
        disabled.setStatus(0);
        when(roleMapper.selectById(8L)).thenReturn(disabled);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(8L)));
        assertEquals("ROLE_DISABLED", ex.code());
    }

    @Test
    void assertCanAssignRoles_普通管理员不能分配系统保留角色() {
        setCaller(2, false, "system:role:list");
        SysRole sysRole = role(1, 1);
        when(roleMapper.selectById(1L)).thenReturn(sysRole);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(1L)));
        assertEquals("ROLE_ASSIGN_FORBIDDEN", ex.code());
    }

    @Test
    void assertCanAssignRoles_普通管理员不能分配权限超出自身的角色() {
        // 调用者只有 system:role:list，但目标角色含 system:user:delete
        setCaller(2, false, "system:role:list");
        SysRole target = role(20, 0);
        when(roleMapper.selectById(20L)).thenReturn(target);
        when(roleMenuMapper.selectMenuIdsByRoleId(20L)).thenReturn(Set.of(200L));
        when(menuMapper.selectPermissionsByMenuIds(Set.of(200L))).thenReturn(Set.of("system:user:delete"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(20L)));
        assertEquals("PERMISSION_NOT_SUBSET", ex.code());
    }

    @Test
    void assertCanAssignRoles_不能分配含adminAll的角色() {
        setCaller(2, false, "system:role:list");
        SysRole target = role(30, 0);
        when(roleMapper.selectById(30L)).thenReturn(target);
        when(roleMenuMapper.selectMenuIdsByRoleId(30L)).thenReturn(Set.of(300L));
        when(menuMapper.selectPermissionsByMenuIds(Set.of(300L))).thenReturn(Set.of("admin:all"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(30L)));
        assertEquals("ROLE_ASSIGN_FORBIDDEN", ex.code());
    }

    @Test
    void assertCanAssignRoles_权限子集校验应用旧权限映射() {
        // 调用者 JWT 中持有新格式 system:role:update；目标角色菜单的 permission 是旧格式 system:role:edit。
        // 归一化后 system:role:edit → system:role:update，与调用者权限一致，应允许分配。
        setCaller(2, false, "system:role:update");
        SysRole target = role(40, 0);
        when(roleMapper.selectById(40L)).thenReturn(target);
        when(roleMenuMapper.selectMenuIdsByRoleId(40L)).thenReturn(Set.of(400L));
        when(menuMapper.selectPermissionsByMenuIds(Set.of(400L))).thenReturn(Set.of("system:role:edit"));

        assertDoesNotThrow(() -> roleService.assertCanAssignRoles(currentPrincipal(), List.of(40L)));
    }

    @Test
    void assertCanAssignRoles_空列表直接放行() {
        setCaller(2, false, "system:role:list");
        assertDoesNotThrow(() -> roleService.assertCanAssignRoles(currentPrincipal(), List.of()));
        assertDoesNotThrow(() -> roleService.assertCanAssignRoles(currentPrincipal(), null));
        verify(roleMapper, never()).selectById(any());
    }

    private IamUserPrincipal currentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return (IamUserPrincipal) auth.getPrincipal();
    }

    /* ========================= page 分页校验 ========================= */

    @Test
    void page_超大size被拒绝() {
        setCaller(1, true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.page(1, 999, null));
        assertEquals("PAGINATION_INVALID", ex.code());
    }

    @Test
    void page_非法current被拒绝() {
        setCaller(1, true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleService.page(0, 10, null));
        assertEquals("PAGINATION_INVALID", ex.code());
    }
}
