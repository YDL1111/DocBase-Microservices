-- =============================================================================
-- V3: 超级管理员变更的数据库级全局互斥锁
--
-- 目的：保护"最后一个有效管理员"校验与停用/删除操作之间的 read-check-write
-- 序列，使其在任意多个 iam-service 实例间严格串行化。
--
-- 原理：sys_admin_mutex 表只有一行守卫记录（id=1）。实例在执行受保护操作的事务
-- 内，首先对该行执行一条 UPDATE（递增 lock_version，业务语义不变）：
--
--     UPDATE sys_admin_mutex SET lock_version = lock_version + 1 WHERE id = 1
--
-- 该语句对主键行的写操作在 MySQL InnoDB 与 H2 上都会获取行级写锁，并持续持有到
-- 当前事务提交或回滚。并发实例的同类 UPDATE 会在该行上阻塞等待，直到持锁事务结
-- 束。因此：
--   - 正常提交：数据库在提交时自动释放行锁，排队事务立即获得锁并读到最新状态；
--   - 实例崩溃 / 连接断开：数据库检测到会话结束，自动回滚并释放行锁，不会遗留
--     死锁。
--
-- 加锁与受保护操作必须在同一个事务内（参见 UserService / AdminMutexMapper），
-- 否则 UPDATE 会立即提交、行锁瞬间释放，失去串行化效果。
--
-- 无需应用层租约：行锁天然随事务生命周期管理，崩溃由数据库自动回收。
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_admin_mutex (
    id           TINYINT      NOT NULL PRIMARY KEY,
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '持锁凭证：每次加锁 +1'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '超级管理员变更全局互斥锁（单行守卫记录）';

-- 守卫记录。INSERT IGNORE 保证幂等：重复迁移不会报错。
INSERT IGNORE INTO sys_admin_mutex (id, lock_version) VALUES (1, 0);
