package com.docbase.iam.user;

import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.AuthService;
import com.docbase.iam.organization.OrganizationService;
import com.docbase.iam.role.RoleService;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.AdminMutexMapper;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 用户管理服务层测试 —— 聚焦资源级授权校验（P0-2）。
 *
 * 采用纯 Mockito（不启动 Spring 上下文），通过手动设置 SecurityContext 模拟调用者身份。
 * 覆盖：非超级管理员禁止操作超级管理员、禁止操作自己、保护最后一个超级管理员。
 */
class UserServiceTest {

    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;
    private TokenStore tokenStore;
    private AdminMutexMapper adminMutexMapper;
    private RoleService roleService;
    private OrganizationService organizationService;
    private PlatformTransactionManager transactionManager;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = mock(AuthService.class);
        tokenStore = mock(TokenStore.class);
        adminMutexMapper = mock(AdminMutexMapper.class);
        roleService = mock(RoleService.class);
        organizationService = mock(OrganizationService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        // 单元测试无真实数据库，桩装守卫行锁定始终"成功"（lockGuardRow 返回 1），
        // 聚焦测试资源级授权与最后管理员保护逻辑本身。
        when(adminMutexMapper.lockGuardRow()).thenReturn(1);
        userService = new UserService(userMapper, userRoleMapper, passwordEncoder, authService,
                tokenStore, adminMutexMapper, roleService, organizationService, transactionManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ========================= 辅助方法 ========================= */

    /** 以指定身份建立 SecurityContext */
    void setCaller(long callerId, boolean admin) {
        Set<String> perms = admin ? Set.of(IamUserPrincipal.ADMIN_ALL_PERMISSION) : Set.of("system:user:update");
        IamUserPrincipal principal = new IamUserPrincipal(callerId, "caller" + callerId, admin, perms);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    SysUser user(long id, int isAdmin) {
        SysUser u = new SysUser();
        u.setUserId(id);
        u.setUsername("user" + id);
        u.setStatus(1);
        u.setIsAdmin(isAdmin);
        return u;
    }

    /* ========================= update ========================= */

    @Test
    void update_普通管理员禁止修改超级管理员() {
        setCaller(2, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.update(user(1, 1), List.of()));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void update_超级管理员可以修改其他超级管理员() {
        setCaller(2, true);
        SysUser admin = user(1, 1);
        when(userMapper.selectById(1L)).thenReturn(admin);

        userService.update(user(1, 1), List.of());

        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    void update_普通管理员可以修改普通用户() {
        setCaller(2, false);
        when(userMapper.selectById(3L)).thenReturn(user(3, 0));

        userService.update(user(3, 0), List.of());

        verify(userMapper).updateById(any(SysUser.class));
    }

    /* ========================= delete ========================= */

    @Test
    void delete_禁止删除自己() {
        setCaller(1, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.delete(1L));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_普通管理员禁止删除超级管理员() {
        setCaller(2, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.delete(1L));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_禁止删除最后一个超级管理员() {
        setCaller(2, true);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));
        // 没有其他"有效"超级管理员（仅 status=1 才计入）
        when(userMapper.selectActiveAdminIds()).thenReturn(List.of(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.delete(1L));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_存在其他超级管理员时允许删除() {
        setCaller(2, true);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));
        // 还有另一位有效超级管理员（userId=9）
        when(userMapper.selectActiveAdminIds()).thenReturn(List.of(1L, 9L));

        userService.delete(1L);

        verify(userMapper).deleteById(any(Long.class));
        verify(authService).invalidateSessions(1L);
    }

    @Test
    void delete_唯一其他管理员已停用时禁止删除当前有效管理员() {
        // 另一个管理员（userId=9）已停用（status=0），不计入有效后备
        setCaller(2, true);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));
        // 互斥锁查询只返回当前这一个有效管理员
        when(userMapper.selectActiveAdminIds()).thenReturn(List.of(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.delete(1L));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).deleteById(any(Long.class));
    }

    /* ========================= changeStatus ========================= */

    @Test
    void changeStatus_禁止停用自己() {
        setCaller(1, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changeStatus(1L, 0));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void changeStatus_普通管理员禁止停用超级管理员() {
        setCaller(2, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changeStatus(1L, 0));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void changeStatus_禁止停用最后一个超级管理员() {
        setCaller(2, true);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));
        // 没有其他有效超级管理员
        when(userMapper.selectActiveAdminIds()).thenReturn(List.of(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changeStatus(1L, 0));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void changeStatus_唯一其他管理员已停用时禁止停用当前有效管理员() {
        setCaller(2, true);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));
        // 互斥锁查询只返回当前这一个有效管理员（另一位已停用，不计入）
        when(userMapper.selectActiveAdminIds()).thenReturn(List.of(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changeStatus(1L, 0));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void changeStatus_启用自己不受最后管理员保护限制() {
        // 启用（status=1）不触发 assertNotLastAdmin，允许操作自己
        setCaller(1, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 0));

        userService.changeStatus(1L, 1);

        verify(userMapper).updateById(any(SysUser.class));
    }

    /* ========================= resetPassword ========================= */

    @Test
    void resetPassword_普通管理员禁止重置超级管理员密码() {
        setCaller(2, false);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.resetPassword(1L, "NewSecret-1"));
        assertEquals("OPERATION_FORBIDDEN", ex.code());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void resetPassword_超级管理员可以重置其他超级管理员密码() {
        setCaller(2, true);
        when(userMapper.selectById(1L)).thenReturn(user(1, 1));
        when(passwordEncoder.encode("NewSecret-1")).thenReturn("encoded");

        userService.resetPassword(1L, "NewSecret-1");

        verify(userMapper).updateById(any(SysUser.class));
        verify(authService).invalidateSessions(1L);
    }

    @Test
    void resetPassword_普通管理员可以重置普通用户密码() {
        setCaller(2, false);
        when(userMapper.selectById(3L)).thenReturn(user(3, 0));
        when(passwordEncoder.encode("NewSecret-1")).thenReturn("encoded");

        userService.resetPassword(3L, "NewSecret-1");

        verify(userMapper).updateById(any(SysUser.class));
    }

    /* ========================= assignRoles 去重 ========================= */

    @Test
    void assignRoles_重复roleId先去重再校验再插入() {
        setCaller(1, true);
        // 同一角色出现两次：去重后只应校验一次、插入一次，避免主键冲突/500。
        // 超级管理员调用时 assertCanAssignRoles 直接放行（admin 短路），无需桩 roleMapper。
        doNothing().when(roleService).assertCanAssignRoles(any(), any());

        userService.assignRoles(10L, List.of(5L, 5L, 5L));

        verify(roleService).assertCanAssignRoles(any(), eq(List.of(5L)));
        verify(userRoleMapper, times(1)).insert(any(SysUserRole.class));
    }
}
