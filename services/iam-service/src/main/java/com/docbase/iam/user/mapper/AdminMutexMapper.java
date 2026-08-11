package com.docbase.iam.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 超级管理员变更的数据库级全局互斥锁。
 *
 * 通过锁定 sys_admin_mutex 表的单行守卫记录（id=1），把"读取有效管理员集合 →
 * 判断是否最后一个 → 执行停用/删除"整段 read-check-write 串行化。详见
 * {@code db/migration/V3__admin_mutex.sql}。
 *
 * 选择数据库级互斥而非 JVM ReentrantLock 的原因：项目具备服务发现（Nacos）与
 * 负载均衡（Gateway），iam-service 允许横向扩容；JVM 锁只能在单个实例内串行化，
 * 两个实例上的并发管理员变更仍可同时通过"最后管理员"校验。数据库级锁以共享的
 * MySQL 行记录为仲裁点，所有实例竞争同一行，从而保证全局至多一个实例处于
 * read-check-write 临界区内。
 *
 * <p><b>加锁语义</b>：{@link #lockGuardRow()} 在业务事务内执行一条守卫行的
 * UPDATE（递增 lock_version，业务语义不变）。该语句对主键行的写操作在 MySQL
 * InnoDB 与 H2 上都会获取行级写锁，并<b>由数据库持有到事务提交或回滚</b>。并发
 * 实例的同类 UPDATE 会在该行上阻塞等待，直到持锁事务结束——从而无需应用层租约：
 * <ul>
 *   <li>正常提交：数据库在提交时自动释放行锁，排队事务立即获得锁并读到最新状态；</li>
 *   <li>实例崩溃 / 连接断开：数据库检测到会话结束，自动回滚并释放持有的行锁，
 *       不会遗留死锁。</li>
 * </ul>
 * 因此 {@link #lockGuardRow()} 必须与受保护的操作在<b>同一个
 * {@code TransactionTemplate}</b> 事务内执行；绝不能在事务外调用，否则 UPDATE
 * 会立即提交、行锁瞬间释放，失去串行化效果。
 */
@Mapper
public interface AdminMutexMapper {

    /**
     * 锁定守卫行，获取管理员变更互斥锁。
     *
     * 通过对主键 id=1 执行 UPDATE 取得行级写锁。该锁由数据库持有到当前事务结束，
     * 并发实例的同类调用会阻塞等待。递增 lock_version 保证语句每次都"真实写"该行
     * （MySQL InnoDB 对相同值的 UPDATE 在 read-committed 下仍持有行锁），同时不留
     * 副作用（版本号仅作持锁凭证）。
     *
     * <p>前置条件：当前已处于事务中（由调用方的事务模板保证）。
     *
     * @return 影响行数（正常为 1；若守卫行缺失则为 0，表示迁移未执行）
     */
    @Update("""
            UPDATE sys_admin_mutex
            SET lock_version = lock_version + 1
            WHERE id = 1""")
    int lockGuardRow();
}
