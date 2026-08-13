package com.docbase.iam.menu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 菜单所有者（owner）生命周期的数据库级全局互斥锁。
 *
 * <p>通过锁定 sys_menu_owner_mutex 表的单行守卫记录（id=1），把 owner 全量替换
 * （转让）、角色停用、角色删除、菜单 owner 继承这四类 read-check-write 串行化，
 * 防止跨实例并发破坏"资源归属与权限授权分离"模型。详见
 * {@code db/migration/V11__menu_owner_mutex.sql}。
 *
 * <p>选择数据库级互斥而非 JVM ReentrantLock 的原因：iam-service 支持横向扩容
 * （Nacos 服务发现 + Gateway 负载均衡），JVM 锁只能在单个实例内串行化。数据库
 * 级锁以共享的 MySQL 行记录为仲裁点，所有实例竞争同一行，从而保证全局至多一个
 * 实例处于临界区内。
 *
 * <p><b>加锁语义</b>：{@link #lockGuardRow()} 在业务事务内执行一条守卫行的
 * UPDATE（递增 lock_version）。该语句对主键行的写操作在 MySQL InnoDB 与 H2 上
 * 都会获取行级写锁，并由数据库持有到事务提交或回滚。并发实例的同类 UPDATE 会
 * 在该行上阻塞等待。因此无需应用层租约：正常提交时数据库自动释放行锁；实例崩溃
 * / 连接断开时数据库自动回滚并回收锁，不会遗留死锁。
 *
 * <p>{@link #lockGuardRow()} 必须与受保护的操作在<b>同一个事务</b>内执行；绝不
 * 能在事务外调用，否则 UPDATE 会立即提交、行锁瞬间释放，失去串行化效果。
 */
@Mapper
public interface MenuOwnerMutexMapper {

    /**
     * 锁定守卫行，获取 owner 生命周期互斥锁。
     *
     * 通过对主键 id=1 执行 UPDATE 取得行级写锁，由数据库持有到当前事务结束，
     * 并发实例的同类调用会阻塞等待。递增 lock_version 保证语句每次都"真实写"
     * 该行（MySQL InnoDB 对相同值的 UPDATE 在 read-committed 下仍持有行锁），
     * 同时不留业务副作用。
     *
     * <p>前置条件：当前已处于事务中（由调用方的事务边界保证）。
     *
     * @return 影响行数（正常为 1；若守卫行缺失则为 0，表示迁移未执行）
     */
    @Update("""
            UPDATE sys_menu_owner_mutex
            SET lock_version = lock_version + 1
            WHERE id = 1""")
    int lockGuardRow();
}
