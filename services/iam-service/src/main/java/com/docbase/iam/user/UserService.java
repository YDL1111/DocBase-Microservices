package com.docbase.iam.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.AuthService;
import com.docbase.iam.role.RoleService;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.AdminMutexMapper;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final TokenStore tokenStore;
    private final TransactionTemplate transactionTemplate;

    /**
     * 角色分配授权：校验调用者是否可以把指定角色分配给用户（角色存在/启用/未删除、
     * 系统保留角色仅超级管理员可分配、目标角色权限⊆调用者权限）。
     * 注入 RoleService 而非在本类重复实现，避免角色授权规则散落多处。
     */
    private final RoleService roleService;

    /**
     * 数据库级全局互斥锁：以 sys_admin_mutex 表的单行守卫记录为仲裁点，把
     * "读取有效管理员集合 → 判断是否最后一个 → 执行停用/删除"整段串行化。
     *
     * 选择数据库级锁而非 JVM ReentrantLock 的原因：项目具备服务发现（Nacos）与
     * 负载均衡（Gateway），iam-service 允许横向扩容；JVM 锁只在单个实例内生效，
     * 两个实例上的并发管理员变更仍可同时通过"最后管理员"校验。数据库级锁用共享的
     * MySQL 行记录做仲裁，所有实例竞争同一行，保证全局至多一个实例处于临界区内。
     *
     * <p><b>加锁语义</b>：{@link AdminMutexMapper#lockGuardRow()} 在业务事务内对
     * 守卫行执行一条 UPDATE（递增 lock_version），取得行级写锁并由数据库持有到事务
     * 提交或回滚。并发实例的同类调用在该行上阻塞等待。因此加锁与受保护操作必须在
     * <b>同一个 {@code TransactionTemplate}</b> 事务内；无需应用层租约——正常提交
     * 时数据库自动释放行锁，实例崩溃 / 连接断开时数据库自动回滚并回收锁，不会遗留
     * 死锁。
     */
    private final AdminMutexMapper adminMutexMapper;

    public UserService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       PasswordEncoder passwordEncoder, AuthService authService,
                       TokenStore tokenStore, AdminMutexMapper adminMutexMapper,
                       RoleService roleService,
                       PlatformTransactionManager transactionManager) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.tokenStore = tokenStore;
        this.adminMutexMapper = adminMutexMapper;
        this.roleService = roleService;
        // 编程式事务：加锁 UPDATE 与 read-check-write 在同一个事务内执行，
        // 行锁由数据库持有到提交/回滚，从而让并发事务一定读到对方已提交的最新状态。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SysUser getById(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    public Page<SysUser> page(long current, long size, String username) {
        Page<SysUser> page = new Page<>(current, size);
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        if (username != null && !username.isBlank()) {
            wrapper.like("username", username);
        }
        wrapper.orderByDesc("create_time");
        Page<SysUser> result = userMapper.selectPage(page, wrapper);
        result.getRecords().forEach(u -> u.setPassword(null));
        return result;
    }

    @Transactional
    public Long create(SysUser user, List<Long> roleIds) {
        if (userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", user.getUsername())) > 0) {
            throw new BusinessException("USERNAME_EXISTS", "username already exists");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setStatus(user.getStatus() != null ? user.getStatus() : 1);
        user.setIsAdmin(user.getIsAdmin() != null ? user.getIsAdmin() : 0);
        userMapper.insert(user);
        assignRoles(user.getUserId(), roleIds);
        return user.getUserId();
    }

    @Transactional
    public void update(SysUser user, List<Long> roleIds) {
        IamUserPrincipal caller = currentPrincipal();
        SysUser existing = userMapper.selectById(user.getUserId());
        if (existing == null) {
            throw new BusinessException("USER_NOT_FOUND", "user not found");
        }
        assertCanOperate(caller, existing);
        existing.setNickname(user.getNickname());
        existing.setEmail(user.getEmail());
        existing.setPhoneNumber(user.getPhoneNumber());
        existing.setSex(user.getSex());
        existing.setRemark(user.getRemark());
        userMapper.updateById(existing);
        if (roleIds != null) {
            userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", user.getUserId()));
            assignRoles(user.getUserId(), roleIds);
            // Role change invalidates permissions cache and sessions
            tokenStore.evictPermissions(user.getUserId());
            tokenStore.bumpAuthVersion(user.getUserId());
        }
    }

    public void delete(Long userId) {
        // 加锁 UPDATE 与 read-check-write 在同一个事务内：事务首先对守卫行
        // 执行 lockGuardRow() 取得行级写锁，数据库持有该锁直到提交或回滚，
        // 并发实例的同类调用在该行上阻塞等待。因此整段"读取有效管理员集合 →
        // 判断是否最后一个 → 执行删除"被严格串行化，无需应用层租约。
        transactionTemplate.execute(status -> {
            lockGuardRow();
            IamUserPrincipal caller = currentPrincipal();
            SysUser existing = userMapper.selectById(userId);
            if (existing == null) {
                throw new BusinessException("USER_NOT_FOUND", "user not found");
            }
            assertCanOperate(caller, existing);
            assertNotSelf(caller, existing);
            assertNotLastAdmin(existing);
            userMapper.deleteById(userId);
            userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
            // Invalidate all sessions for deleted user
            authService.invalidateSessions(userId);
            return null;
        });
    }

    public void changeStatus(Long userId, Integer status) {
        // 停用操作需持有数据库级全局互斥锁直至事务提交，串行化"读取有效管理
        // 员集合 → 判断是否最后一个 → 执行停用"。启用操作不涉及最后管理员保护，
        // 无需加锁。
        boolean needLock = (status != null && status == 0);
        if (!needLock) {
            transactionTemplate.execute(s -> {
                changeStatusInternal(userId, status);
                return null;
            });
        } else {
            transactionTemplate.execute(s -> {
                lockGuardRow();
                changeStatusInternal(userId, status);
                return null;
            });
        }
    }

    private void changeStatusInternal(Long userId, Integer status) {
        IamUserPrincipal caller = currentPrincipal();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("USER_NOT_FOUND", "user not found");
        }
        assertCanOperate(caller, user);
        if (status != null && status == 0) {
            // 停用操作：禁止停用自己，且保护最后一个超级管理员
            assertNotSelf(caller, user);
            assertNotLastAdmin(user);
        }
        user.setStatus(status);
        userMapper.updateById(user);
        // Disabling a user invalidates all their sessions immediately
        if (status != null && status == 0) {
            authService.invalidateSessions(userId);
        }
    }

    /**
     * 在事务内锁定守卫行，获取管理员变更的行级写锁。
     *
     * 通过对守卫行（主键 id=1）执行 UPDATE 取得行级写锁；数据库持有该锁直到当前
     * 事务提交或回滚，并发实例的同类调用在该行上阻塞等待。
     *
     * <p>前置条件：当前已处于事务中（由调用方的事务模板保证）。若守卫行不存在
     * （迁移未执行），抛出 MIGRATION_MISSING 提示运行 Flyway。
     */
    private void lockGuardRow() {
        int affected = adminMutexMapper.lockGuardRow();
        if (affected == 0) {
            throw new BusinessException("MIGRATION_MISSING",
                    "sys_admin_mutex guard row missing — run Flyway migration V3");
        }
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        IamUserPrincipal caller = currentPrincipal();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("USER_NOT_FOUND", "user not found");
        }
        assertCanOperate(caller, user);
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        // Password change invalidates all prior sessions immediately
        authService.invalidateSessions(userId);
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null) return;
        // 先去重：避免重复 ID 通过授权检查后重复插入 sys_user_role 主键，触发数据库异常/500。
        // 数据库唯一键 (user_id, role_id) 继续作为最终防线。
        List<Long> deduped = roleIds.stream().distinct().toList();
        // 角色分配授权：校验每个角色存在/启用/未删除、系统保留角色仅超级管理员可分配、
        // 目标角色的有效权限是调用者权限的子集（防纵向提权）。
        IamUserPrincipal caller = currentPrincipal();
        roleService.assertCanAssignRoles(caller, deduped);
        for (Long roleId : deduped) {
            userRoleMapper.insert(new SysUserRole(userId, roleId));
        }
        // Role assignment changes invalidate permissions
        tokenStore.evictPermissions(userId);
        tokenStore.bumpAuthVersion(userId);
    }

    public List<Long> getRoleIds(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<SysUserRole>().eq("user_id", userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    /* ========================= 资源级授权校验 ========================= */

    /**
     * 解析当前认证主体。
     *
     * 所有受保护操作（修改/删除/启停/重置密码）都通过此方法获取调用者身份，
     * 避免在各方法中散落 SecurityContextHolder 访问。
     */
    private IamUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof IamUserPrincipal principal)) {
            throw new BusinessException("UNAUTHENTICATED", "no authenticated principal");
        }
        return principal;
    }

    /** 判断用户是否为超级管理员（is_admin=1） */
    private boolean isAdmin(SysUser user) {
        return user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1;
    }

    /**
     * 资源级访问控制：非超级管理员禁止操作超级管理员账号。
     *
     * 这是防御提权的关键校验：即便普通 system_admin 拥有 system:user:* 权限码，
     * 也不能对 is_admin=1 的目标执行任何敏感操作，从而切断
     * "重置超级管理员密码 → 登录 → 取得 admin:all" 的提权链路。
     */
    private void assertCanOperate(IamUserPrincipal caller, SysUser target) {
        if (caller.admin()) return; // 超级管理员不受资源级限制
        if (isAdmin(target)) {
            throw new BusinessException("OPERATION_FORBIDDEN",
                    "only super-admin can operate on an admin user");
        }
    }

    /** 禁止删除或停用自己 */
    private void assertNotSelf(IamUserPrincipal caller, SysUser target) {
        if (caller.userId().equals(target.getUserId())) {
            throw new BusinessException("OPERATION_FORBIDDEN", "cannot operate on yourself");
        }
    }

    /**
     * 保护最后一个"有效"超级管理员：禁止删除或停用。
     *
     * "有效"超级管理员 = is_admin=1 AND status=1 AND deleted=0。已停用的管理员
     * 不计入后备，避免"唯一其他管理员已停用时仍允许删除当前最后一个有效管理员"。
     *
     * 并发安全：本方法仅做"读取有效管理员集合 → 判断是否最后一个"的校验，
     * 自身不加锁。调用方（{@link #delete} / {@link #changeStatus}）必须在与
     * {@link AdminMutexMapper#lockGuardRow()} 同一个数据库事务内调用本方法——
     * lockGuardRow() 对守卫行的 UPDATE 会获取行级写锁并持有到事务提交/回滚，
     * 从而把整段读取-判断-写入严格串行化。这样两个并发操作不可能同时看到"还有
     * 一个管理员"后都通过检查。
     */
    private void assertNotLastAdmin(SysUser target) {
        if (!isAdmin(target)) return;
        // 调用方已持互斥锁并处于事务内，此处读取不会被并发修改打断
        List<Long> activeAdminIds = userMapper.selectActiveAdminIds();
        long otherActiveAdmins = activeAdminIds.stream()
                .filter(id -> !id.equals(target.getUserId()))
                .count();
        if (otherActiveAdmins == 0) {
            throw new BusinessException("OPERATION_FORBIDDEN",
                    "cannot delete or disable the last active super-admin");
        }
    }
}
