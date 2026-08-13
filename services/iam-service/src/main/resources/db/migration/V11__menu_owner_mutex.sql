-- =============================================================================
-- V11: 菜单所有者（owner）生命周期的数据库级全局互斥锁
--
-- 背景：Phase 5C1 引入菜单 owner 的查询/全量替换（转让）接口，并在角色删除/停用
-- 前增加"最后一个有效 owner"校验。这些操作之间必须共享同一套跨实例互斥协议，
-- 否则会破坏"资源归属与权限授权分离"的安全模型：
--   - 角色删除/停用 vs owner 转让：可能"检查时菜单还有其它有效 owner，提交后却没有"，
--     让菜单变成普通管理员无法管理的孤儿资源。
--   - 角色删除 vs 菜单 owner 继承（create 子菜单）：可能"角色清理后又插入指向已删除
--     角色的 owner 行"，留下指向已删除角色的孤儿归属。
--
-- 原理：与 V3__admin_mutex.sql 相同。sys_menu_owner_mutex 表只有一行守卫记录
-- （id=1）。受保护操作在同一事务内先对其执行一条 UPDATE（递增 lock_version），
-- 该语句对主键行的写操作在 MySQL InnoDB 与 H2 上都会取得行级写锁，并由数据库
-- 持有到事务提交或回滚。并发实例的同类 UPDATE 在该行上阻塞等待，从而把
-- "读取有效 owner 集合 → 校验是否最后一个 → 执行"整段 read-check-write 串行化。
--
-- 覆盖的受保护操作（必须全部在此锁的临界区内执行）：
--   1. 菜单 owner 全量替换/转让（MenuService.replaceOwners）
--   2. 角色停用（RoleService.changeStatus，status=0 时）
--   3. 角色删除（RoleService.delete）
--   4. 菜单 owner 继承（MenuService.create 的非超级管理员分支）
--
-- 加锁与受保护操作必须在同一个事务内（否则 UPDATE 会立即提交、行锁瞬间释放）。
-- 无需应用层租约：行锁天然随事务生命周期管理，崩溃由数据库自动回收。
--
-- 幂等性：
--   - 建表由 Flyway schema_history 保证只运行一次。
--   - INSERT IGNORE 保证守卫记录幂等。
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_menu_owner_mutex (
    id           TINYINT      NOT NULL PRIMARY KEY,
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '持锁凭证：每次加锁 +1'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '菜单所有者生命周期全局互斥锁（单行守卫记录）';

-- 守卫记录。INSERT IGNORE 保证幂等：重复迁移不会报错。
INSERT IGNORE INTO sys_menu_owner_mutex (id, lock_version) VALUES (1, 0);
