package com.docbase.iam.user;

import com.docbase.common.core.BusinessException;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.AdminMutexMapper;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.TestUserCleanupMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 最后有效管理员保护的并发测试（P0）。
 *
 * 使用真实 H2 数据库 + 真实线程，验证数据库级互斥锁（原子条件 UPDATE）的串行化：
 * 两个有效管理员互相停用对方时，只能有一个成功，
 * 从而保证始终至少保留一个有效超级管理员。
 *
 * 注意：此测试依赖数据库级互斥锁（见 AdminMutexMapper / V3__admin_mutex.sql），
 * 必须在 @SpringBootTest 下运行（不能用 Mockito）。
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceConcurrencyTest {

    @Autowired
    UserService userService;

    @Autowired
    SysUserMapper userMapper;

    /**
     * 测试专用物理删除 Mapper（src/test，不进生产镜像）。
     * 仅用于测试前后的破坏性清理；生产 SysUserMapper 不再暴露物理删除能力。
     */
    @Autowired
    TestUserCleanupMapper testUserCleanupMapper;

    @Autowired
    AdminMutexMapper adminMutexMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    /**
     * 测试 profile 禁用了 Redis，提供 mock StringRedisTemplate 以满足
     * TokenStore 等的依赖注入（本测试不实际调用它们）。
     */
    @MockitoBean
    StringRedisTemplate redisTemplate;

    /**
     * 沙箱不存在 .local/keys/*.pem，用临时生成的密钥替换 JwtProperties，
     * 使 Spring 上下文能在无外部密钥文件的情况下启动。
     */
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

    private final List<Long> createdUserIds = new ArrayList<>();

    /**
     * 每个测试前物理清空 sys_user 表。
     *
     * 共享内存 H2 跨测试类复用数据。其它测试（如 UserControllerValidationTest）通过
     * loginAsAdmin 插入 is_admin=1 用户后并不清理，若遗留到本测试，会扩大
     * "有效管理员集合"，使"两个并发停用不能同时成功"的前提（仅存在 A、B 两个
     * 有效管理员）不再成立。清空后本测试精确控制有效管理员集合，保证可重复性。
     */
    @BeforeEach
    void setUp() {
        testUserCleanupMapper.deleteAllPhysically();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        for (Long id : createdUserIds) {
            try {
                // 物理删除：逻辑删除只置 deleted=1，行仍物理存在，唯一索引仍会
                // 阻止重新插入同名用户，导致跨测试类复用 H2 时 DuplicateKey。
                testUserCleanupMapper.deletePhysicallyById(id);
            } catch (Exception ignored) {
                // ignore cleanup errors
            }
        }
        createdUserIds.clear();
    }

    /**
     * 插入一个有效超级管理员并记录 ID 以便清理。
     */
    private SysUser insertActiveAdmin(long id) {
        SysUser u = new SysUser();
        u.setUserId(id);
        u.setUsername("admin" + id);
        u.setNickname("Admin " + id);
        u.setPassword("encoded");
        u.setStatus(1);
        u.setIsAdmin(1);
        u.setDeleted(0);
        // 指定 userId 以控制测试中的身份
        userMapper.insert(u);
        createdUserIds.add(id);
        return u;
    }

    /** 以指定管理员身份建立线程本地的 SecurityContext */
    private static void setCaller(long callerId, boolean admin) {
        IamUserPrincipal principal = new IamUserPrincipal(
                callerId, "admin" + callerId, admin,
                admin ? Set.of(IamUserPrincipal.ADMIN_ALL_PERMISSION) : Set.of("system:user:update"));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void 两个并发停用不能同时成功_至少保留一个有效管理员() throws Exception {
        // 使用高位显式 ID（99001/99002），避免与跨测试类复用的 H2 中
        // 其它测试自动生成的 ID（1、2、3…）发生主键 / 唯一索引冲突。
        final long idA = 99001L;
        final long idB = 99002L;

        // 准备两个有效超级管理员：线程 1 用 admin A 停用 admin B，
        // 线程 2 用 admin B 停用 admin A。
        insertActiveAdmin(idA);
        insertActiveAdmin(idB);

        // 确认初始状态：两个都有效
        assertEquals(1, userMapper.selectById(idA).getStatus());
        assertEquals(1, userMapper.selectById(idB).getStatus());

        // 模拟 Redis：changeStatus 内部会调用 invalidateSessions → bumpAuthVersion
        // （opsForValue.increment / expire）与 evictPermissions（redis.delete）。
        // 未桩装的 mock 会返回 null 导致 NPE，必须提前 stub。
        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger forbidden = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<String> diagT1 = new java.util.concurrent.atomic.AtomicReference<>("?");
        java.util.concurrent.atomic.AtomicReference<String> diagT2 = new java.util.concurrent.atomic.AtomicReference<>("?");

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        // 线程 1：admin A 停用 admin B
        pool.submit(() -> {
            setCaller(idA, true);
            try {
                barrier.await(5, TimeUnit.SECONDS);
                userService.changeStatus(idB, 0);
                successes.incrementAndGet();
                diagT1.set("SUCCESS");
            } catch (BusinessException e) {
                if ("OPERATION_FORBIDDEN".equals(e.code())) forbidden.incrementAndGet();
                diagT1.set("FORBIDDEN:" + e.code());
            } catch (Exception e) {
                diagT1.set("EX:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            } finally {
                done.countDown();
                SecurityContextHolder.clearContext();
            }
        });

        // 线程 2：admin B 停用 admin A
        pool.submit(() -> {
            setCaller(idB, true);
            try {
                barrier.await(5, TimeUnit.SECONDS);
                userService.changeStatus(idA, 0);
                successes.incrementAndGet();
                diagT2.set("SUCCESS");
            } catch (BusinessException e) {
                if ("OPERATION_FORBIDDEN".equals(e.code())) forbidden.incrementAndGet();
                diagT2.set("FORBIDDEN:" + e.code());
            } catch (Exception e) {
                diagT2.set("EX:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            } finally {
                done.countDown();
                SecurityContextHolder.clearContext();
            }
        });

        assertTrue(done.await(15, TimeUnit.SECONDS), "并发测试未在超时内完成");
        pool.shutdown();

        System.out.println("DIAGConcurrency t1=" + diagT1.get() + " t2=" + diagT2.get()
                + " successes=" + successes.get() + " forbidden=" + forbidden.get());

        // 核心断言：两个并发停用不能都成功，必须恰好一个成功、一个被拒
        assertEquals(1, successes.get(), "两个并发停用应恰好一个成功");
        assertEquals(1, forbidden.get(), "两个并发停用应恰好一个被 OPERATION_FORBIDDEN 拒绝");

        // 最终状态：仍恰好一个有效管理员
        long activeAdmins = List.of(userMapper.selectById(idA), userMapper.selectById(idB))
                .stream().filter(u -> u.getStatus() == 1).count();
        assertEquals(1, activeAdmins, "并发操作后必须仍保留一个有效超级管理员");
    }

    /**
     * 回归测试：数据库行锁必须<b>持有到事务提交</b>，而不是在某个应用层租约到期后
     * 提前释放。
     *
     * 场景：线程 1 开启一个长事务（获得守卫行锁后故意持有 1.5 秒再提交），
     * 线程 2 在同一时间窗口内尝试受保护操作。若锁被持有到提交，线程 2 的受保护
     * 操作耗时应覆盖线程 1 的持有时长（≈1.5 秒）；若存在"租约到期即提前释放"的
     * 缺陷，线程 2 将在租约（如 30 秒设计值被错误缩短，或 unlock 被旧持有者误触发）
     * 之后提前进入临界区，耗时远小于持有时长。
     *
     * 这是对"旧持锁者超时后解锁新持锁者"缺陷的直接回归：行锁方案下，持锁事务
     * 提交前锁不可能被第三方取得，因此线程 2 必须等待完整的持有窗口。
     */
    @Test
    void 长事务持有行锁期间_并发请求必须等待至提交_不得提前进入临界区() throws Exception {
        final long idA = 99001L;
        final long idB = 99002L;
        insertActiveAdmin(idA);
        insertActiveAdmin(idB);

        // 桩装 Redis，避免 invalidateSessions NPE。
        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        // 持锁时长：模拟一个明显长于任何"错误缩短的租约"的业务事务。
        final long holdMs = 1500;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holderLocked = new CountDownLatch(1);
        AtomicLong waiterElapsedMs = new AtomicLong(-1);
        AtomicReference<String> waiterResult = new AtomicReference<>("?");

        // 线程 1：开启事务 → 获得守卫行锁 → 持有 holdMs → 提交释放。
        // 故意不执行任何管理员变更，仅验证"行锁持有窗口"本身。
        pool.submit(() -> {
            transactionTemplate().execute(status -> {
                adminMutexMapper.lockGuardRow();
                holderLocked.countDown();
                try {
                    Thread.sleep(holdMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null; // 提交时数据库自动释放行锁
            });
            return null;
        });

        // 线程 2：等线程 1 获锁后，尝试受保护操作，测量从调用到返回的耗时。
        pool.submit(() -> {
            try {
                holderLocked.await(5, TimeUnit.SECONDS);
                setCaller(idB, true);
                long start = System.currentTimeMillis();
                userService.changeStatus(idA, 0); // 受保护操作，内部同样先锁守卫行
                waiterElapsedMs.set(System.currentTimeMillis() - start);
                waiterResult.set("SUCCESS");
            } catch (BusinessException e) {
                waiterElapsedMs.set(System.currentTimeMillis());
                waiterResult.set("FORBIDDEN:" + e.code());
            } catch (Exception e) {
                waiterResult.set("EX:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        });

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "长事务回归测试未在超时内完成");

        System.out.println("DIAGLongTx holdMs=" + holdMs
                + " waiterResult=" + waiterResult.get()
                + " waiterElapsedMs=" + waiterElapsedMs.get());

        // 核心断言：线程 2 的受保护操作必须等待线程 1 的整个持有窗口，
        // 耗时应覆盖 holdMs（允许少量计时误差）。若锁被提前释放，耗时将远小于 holdMs。
        assertTrue(waiterElapsedMs.get() >= holdMs - 200,
                "并发请求应阻塞至长事务提交；实际耗时=" + waiterElapsedMs.get()
                        + "ms，持有窗口=" + holdMs + "ms（若提前进入临界区则耗时远小于持有窗口）");
    }

    /** 为测试构造一个独立事务模板（与 service 内部模板隔离，避免事务嵌套混淆）。 */
    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }
}
