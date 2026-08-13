package com.docbase.iam.menu;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.domain.SysMenuOwnerRole;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.menu.mapper.SysMenuOwnerRoleMapper;
import com.docbase.iam.role.RoleService;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.dto.ChangeRoleStatusRequest;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import com.docbase.iam.user.mapper.TestUserCleanupMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 菜单 owner 生命周期并发测试（Phase 5C1，确定性交错版）。
 *
 * <p>与上一版"同时起跑"不同，本测试通过 {@code @MockitoBean} 替换
 * {@link OwnerLifecycleLockHook} 接缝固定危险交错顺序：T1 在获取
 * {@code sys_menu_owner_mutex} 之前被 latch 暂停（此时 T1 事务已开启但未建立一致性读快照），主线程完成"另一个操作"并提交后放行 T1。
 * 这精确复现审查要求的 P0 时序——锁前查询/锁后写入必须基于拿到锁时刻的最新数据。
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>转让等待锁期间，候选角色被删除 → 转让必须拒绝（ROLE_INVALID），无孤儿 owner；</li>
 *   <li>角色删除等待锁期间，owner 已转让 → 删除线程锁内重查，删除成功且无孤儿；</li>
 *   <li>角色停用等待锁期间，owner 已转让 → 停用线程锁内重查，停用成功；</li>
 *   <li>菜单删除等待锁期间，owner 已转让 → 删除线程锁内重查并清理 owner，无孤儿；</li>
 *   <li>转让等待锁期间，菜单已被删除 → 转让必须拒绝（MENU_NOT_FOUND），无孤儿 owner；</li>
 *   <li>owner 身份下的菜单写等待锁期间，归属被撤销 → 旧 owner 的更新被拒绝（MENU_NOT_FOUND）。</li>
 * </ol>
 *
 * <p>断言均为确定性：无论哪一方先进入临界区，最终都不会出现"无有效 owner"或
 * "指向已删除角色/已删除菜单的孤儿归属"。
 */
@SpringBootTest
@ActiveProfiles("test")
class MenuOwnerConcurrencyTest {

    @Autowired MenuService menuService;
    @Autowired RoleService roleService;
    @Autowired SysMenuMapper menuMapper;
    @Autowired SysRoleMapper roleMapper;
    @Autowired SysMenuOwnerRoleMapper ownerRoleMapper;
    @Autowired SysUserRoleMapper userRoleMapper;
    @Autowired TestUserCleanupMapper testUserCleanupMapper;
    /** 物理查询（selectById 受 MyBatis-Plus @TableLogic 过滤，删除后返回 null，断言用 JdbcTemplate）。 */
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StringRedisTemplate redisTemplate;

    /**
     * owner 锁前回调接缝：生产默认 no-op，测试用 @MockitoBean 替换为固定交错逻辑
     * （第一次 beforeLock() 暂停 T1，主线程完成另一操作后放行）。避免在生产单例上
     * 暴露可变的 public 测试钩子。
     */
    @MockitoBean OwnerLifecycleLockHook ownerLockHook;

    /** T1 已到达锁前暂停点（主线程据此执行另一操作）。 */
    private CountDownLatch t1Entered;
    /** 主线程放行 T1。 */
    private CountDownLatch releaseT1;
    /** 只让第一个 lockGuardRow 调用暂停（避免主线程的操作被阻塞）。 */
    private AtomicInteger hookCalls;

    @BeforeEach
    void setUp() {
        testUserCleanupMapper.deleteAllPhysically();
        ownerRoleMapper.delete(null);
        userRoleMapper.delete(null);
        roleMapper.delete(null);
        menuMapper.delete(null);

        t1Entered = new CountDownLatch(1);
        releaseT1 = new CountDownLatch(1);
        hookCalls = new AtomicInteger();
        // 通过 bean 替换注入固定交错逻辑：只有第一个 beforeLock() 暂停（T1），
        // 后续调用（主线程的另一操作）不阻塞。
        doAnswer(inv -> {
            if (hookCalls.getAndIncrement() == 0) {
                t1Entered.countDown();
                try {
                    releaseT1.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        }).when(ownerLockHook).beforeLock();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        testUserCleanupMapper.deleteAllPhysically();
        ownerRoleMapper.delete(null);
        userRoleMapper.delete(null);
        roleMapper.delete(null);
        menuMapper.delete(null);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        JwtProperties testJwtProperties() throws IOException {
            KeyPair pair = com.docbase.iam.security.TestKeys.generate();
            Path dir = com.docbase.iam.security.TestKeys.writeTempKeyPair(pair);
            return new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "docbase-iam", "30m", "7d");
        }
    }

    /* ========================= 辅助方法 ========================= */

    private static void setCaller(long userId, boolean admin, String... perms) {
        Set<String> permissions = admin
                ? Set.of(IamUserPrincipal.ADMIN_ALL_PERMISSION) : Set.of(perms);
        IamUserPrincipal principal = new IamUserPrincipal(userId, "u" + userId, admin, permissions);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private SysMenu insertMenu(String routerName) {
        SysMenu m = new SysMenu();
        m.setParentId(0L);
        m.setMenuName("M " + routerName);
        m.setMenuType(2);
        m.setRouterName(routerName);
        m.setPath("/" + routerName.toLowerCase());
        m.setPermission("");
        m.setIsButton(0);
        m.setStatus(1);
        m.setIsSystem(0);
        m.setSortNum(1);
        m.setMetaInfo("{}");
        m.setDeleted(0);
        menuMapper.insert(m);
        return m;
    }

    private SysRole insertRole(String key, int status, int deleted) {
        SysRole r = new SysRole();
        r.setRoleName("role_" + key);
        r.setRoleKey(key);
        r.setStatus(status);
        r.setIsSystem(0);
        r.setDeleted(deleted);
        roleMapper.insert(r);
        return r;
    }

    private long ownerRowCount(Long menuId) {
        return ownerRoleMapper.selectCount(
                new QueryWrapper<SysMenuOwnerRole>().eq("menu_id", menuId));
    }

    /** 启动 T1（锁前暂停），等待其停在暂停点。 */
    private ExecutorService startT1AndWaitForHook(Runnable t1) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        pool.submit(t1);
        assertTrue(t1Entered.await(10, TimeUnit.SECONDS), "T1 应停在锁前暂停点");
        return pool;
    }

    private void releaseT1AndShutdown(ExecutorService pool) throws Exception {
        releaseT1.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "T1 未在超时内完成");
    }

    /* ========================= 1) 转让 vs 角色删除（角色先被删除） ========================= */

    @Test
    void 转让等待锁期间_候选角色被删除_转让拒绝且不产生孤儿() throws Exception {
        SysMenu m = insertMenu("RaceDelCand");
        SysRole a = insertRole("race_del_cand_a", 1, 0);
        SysRole b = insertRole("race_del_cand_b", 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(m.getMenuId(), a.getRoleId()));

        AtomicReference<String> t1Result = new AtomicReference<>("?");
        ExecutorService pool = startT1AndWaitForHook(() -> {
            setCaller(1L, true);
            try {
                menuService.replaceOwners(m.getMenuId(), List.of(b.getRoleId()));
                t1Result.set("SUCCESS");
            } catch (BusinessException e) {
                t1Result.set("BIZ:" + e.code());
            } catch (Exception e) {
                t1Result.set("EX:" + e.getClass().getSimpleName());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // T1 停在锁前（尚未查询/校验角色 B）：主线程删除 B，提交。
        setCaller(2L, true);
        roleService.delete(b.getRoleId());
        SecurityContextHolder.clearContext();

        releaseT1AndShutdown(pool);

        // T1 恢复后拿锁、锁内重查：B 已删除 → ROLE_INVALID，绝不插入指向已删除 B 的 owner。
        assertEquals("BIZ:ROLE_INVALID", t1Result.get(),
                "转让必须在锁内重新校验候选角色，拒绝已删除角色");
        // 原 owner A 保持，无 (m, B) 行
        assertEquals(Set.of(a.getRoleId()), ownerRoleMapper.selectOwnerRoleIds(m.getMenuId()),
                "转让失败后原 owner 应保持不变");
    }

    /* ========================= 2) 角色删除 vs 转让（转让先完成） ========================= */

    @Test
    void 角色删除等待锁期间_owner已转让_删除成功且无孤儿() throws Exception {
        SysMenu m = insertMenu("RaceDelAfter");
        SysRole a = insertRole("race_del_after_a", 1, 0);
        SysRole b = insertRole("race_del_after_b", 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(m.getMenuId(), a.getRoleId()));

        AtomicReference<String> t1Result = new AtomicReference<>("?");
        ExecutorService pool = startT1AndWaitForHook(() -> {
            setCaller(1L, true);
            try {
                roleService.delete(a.getRoleId());
                t1Result.set("SUCCESS");
            } catch (BusinessException e) {
                t1Result.set("BIZ:" + e.code());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // T1 停在锁前：主线程把 owner 从 [A] 转让为 [B]，提交。
        setCaller(2L, true);
        menuService.replaceOwners(m.getMenuId(), List.of(b.getRoleId()));
        SecurityContextHolder.clearContext();

        releaseT1AndShutdown(pool);

        // T1 恢复后锁内重查：A 已不是 m 的 owner → 删除 A 不再触发最后-owner 拒绝。
        assertEquals("SUCCESS", t1Result.get(), "转让后 A 不再是最后 owner，删除 A 应成功");
        // 最终 m 的有效 owner 恰好是 [B]，且 A 已删除、无孤儿。
        assertEquals(Set.of(b.getRoleId()), ownerRoleMapper.selectOwnerRoleIds(m.getMenuId()),
                "最终有效 owner 应恰好为 B");
        Integer deletedA = jdbc.queryForObject(
                "SELECT deleted FROM sys_role WHERE role_id = ?", Integer.class, a.getRoleId());
        assertEquals(1, deletedA, "角色 A 应已被删除");
    }

    /* ========================= 3) 角色停用 vs 转让（转让先完成） ========================= */

    @Test
    void 角色停用等待锁期间_owner已转让_停用成功() throws Exception {
        SysMenu m = insertMenu("RaceStopAfter");
        SysRole a = insertRole("race_stop_after_a", 1, 0);
        SysRole b = insertRole("race_stop_after_b", 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(m.getMenuId(), a.getRoleId()));

        AtomicReference<String> t1Result = new AtomicReference<>("?");
        ExecutorService pool = startT1AndWaitForHook(() -> {
            setCaller(1L, true);
            try {
                roleService.changeStatus(a.getRoleId(), new ChangeRoleStatusRequest(0));
                t1Result.set("SUCCESS");
            } catch (BusinessException e) {
                t1Result.set("BIZ:" + e.code());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        setCaller(2L, true);
        menuService.replaceOwners(m.getMenuId(), List.of(b.getRoleId()));
        SecurityContextHolder.clearContext();

        releaseT1AndShutdown(pool);

        assertEquals("SUCCESS", t1Result.get(), "转让后 A 不再是最后 owner，停用 A 应成功");
        Integer statusA = jdbc.queryForObject(
                "SELECT status FROM sys_role WHERE role_id = ?", Integer.class, a.getRoleId());
        assertEquals(0, statusA, "角色 A 应已停用");
        assertEquals(Set.of(b.getRoleId()), ownerRoleMapper.selectOwnerRoleIds(m.getMenuId()),
                "最终有效 owner 应恰好为 B");
    }

    /* ========================= 4) 菜单删除 vs 转让（转让先完成） ========================= */

    @Test
    void 菜单删除等待锁期间_owner已转让_删除清理全部owner无孤儿() throws Exception {
        SysMenu m = insertMenu("RaceMenuDel");
        SysRole a = insertRole("race_menu_del_a", 1, 0);
        SysRole b = insertRole("race_menu_del_b", 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(m.getMenuId(), a.getRoleId()));

        AtomicReference<String> t1Result = new AtomicReference<>("?");
        ExecutorService pool = startT1AndWaitForHook(() -> {
            setCaller(1L, true);
            try {
                menuService.delete(m.getMenuId());
                t1Result.set("SUCCESS");
            } catch (BusinessException e) {
                t1Result.set("BIZ:" + e.code());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // T1 停在锁前：主线程把 owner 转让为 [B]，提交。
        setCaller(2L, true);
        menuService.replaceOwners(m.getMenuId(), List.of(b.getRoleId()));
        SecurityContextHolder.clearContext();

        releaseT1AndShutdown(pool);

        // T1 恢复后锁内重查菜单（仍存在）→ 删除成功，且清理掉转让写入的 owner 行。
        assertEquals("SUCCESS", t1Result.get());
        Integer deletedMenu = jdbc.queryForObject(
                "SELECT deleted FROM sys_menu WHERE menu_id = ?", Integer.class, m.getMenuId());
        assertEquals(1, deletedMenu, "菜单应已软删除");
        assertEquals(0L, ownerRowCount(m.getMenuId()),
                "删除菜单后不得残留指向已删除菜单的孤儿归属（含转让刚写入的 owner）");
    }

    /* ========================= 5) 转让 vs 菜单删除（菜单先被删除） ========================= */

    @Test
    void 转让等待锁期间_菜单已被删除_转让拒绝且不产生孤儿() throws Exception {
        SysMenu m = insertMenu("RaceTransferDel");
        SysRole a = insertRole("race_transfer_del_a", 1, 0);
        SysRole b = insertRole("race_transfer_del_b", 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(m.getMenuId(), a.getRoleId()));

        AtomicReference<String> t1Result = new AtomicReference<>("?");
        ExecutorService pool = startT1AndWaitForHook(() -> {
            setCaller(1L, true);
            try {
                menuService.replaceOwners(m.getMenuId(), List.of(b.getRoleId()));
                t1Result.set("SUCCESS");
            } catch (BusinessException e) {
                t1Result.set("BIZ:" + e.code());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // T1 停在锁前：主线程删除菜单（清理 owner + 软删除），提交。
        setCaller(2L, true);
        menuService.delete(m.getMenuId());
        SecurityContextHolder.clearContext();

        releaseT1AndShutdown(pool);

        // T1 恢复后锁内重查：菜单已删除 → MENU_NOT_FOUND，绝不重新插入 owner。
        assertEquals("BIZ:MENU_NOT_FOUND", t1Result.get(),
                "转让必须在锁内重新校验菜单存在性，拒绝已删除菜单");
        assertEquals(0L, ownerRowCount(m.getMenuId()),
                "不得留下指向已删除菜单的孤儿归属");
    }

    /* ========================= 6) owner 撤销 vs owner 身份下的菜单写 ========================= */

    @Test
    void owner归属等待锁期间被撤销_旧owner的菜单更新被拒绝() throws Exception {
        SysMenu m = insertMenu("RaceRevoke");
        SysRole a = insertRole("race_revoke_a", 1, 0);
        SysRole b = insertRole("race_revoke_b", 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(m.getMenuId(), a.getRoleId()));
        // 旧 owner 用户 UA（userId=90001）是角色 A 成员。
        userRoleMapper.insert(new SysUserRole(90001L, a.getRoleId()));

        AtomicReference<String> t1Result = new AtomicReference<>("?");
        ExecutorService pool = startT1AndWaitForHook(() -> {
            setCaller(90001L, false, "system:menu:update");
            try {
                menuService.update(m.getMenuId(), new com.docbase.iam.menu.dto.UpdateMenuRequest(
                        m.getParentId(), "被撤销的更新", m.getMenuType(),
                        m.getRouterName(), m.getPath(), "",
                        m.getMetaInfo(), 0, 1, null));
                t1Result.set("SUCCESS");
            } catch (BusinessException e) {
                t1Result.set("BIZ:" + e.code());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // T1 停在锁前：主线程把 owner 从 [A] 转让为 [B]（撤销 UA 的管理归属），提交。
        setCaller(2L, true);
        menuService.replaceOwners(m.getMenuId(), List.of(b.getRoleId()));
        SecurityContextHolder.clearContext();

        releaseT1AndShutdown(pool);

        // T1 恢复后锁内重查 owner：UA 已不再是 owner → MENU_NOT_FOUND，菜单未被修改。
        assertEquals("BIZ:MENU_NOT_FOUND", t1Result.get(),
                "归属被撤销后，旧 owner 的菜单写操作必须在锁内被拒绝");
        assertEquals("M RaceRevoke", menuMapper.selectById(m.getMenuId()).getMenuName(),
                "菜单不得被已撤销归属的旧 owner 修改");
    }
}
