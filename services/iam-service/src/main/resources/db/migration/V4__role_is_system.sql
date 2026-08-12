-- =============================================================================
-- V4: 角色"系统保留"标记
--
-- 目的：为 sys_role 增加 is_system 列，标记由 Flyway 引导脚本预置的系统保留角色。
-- 系统保留角色禁止非超级管理员修改、停用或删除，也不允许通过菜单授权取得 admin:all。
--
-- is_system 比 roleName 更可靠：roleName 可能被本地化或重命名，而 is_system
-- 是数据库层面的不可变标记，仅由迁移脚本写入，接口创建的角色一律 is_system=0。
--
-- 安全语义：
--   - is_system=1 的角色：仅超级管理员（is_admin=1）可修改/停用/删除，且仅超级管理员可分配。
--   - 接口创建的角色：is_system 强制为 0，普通管理员无法伪造系统保留角色。
--   - 该标记与 admin:all 共同构成双层保护：即便绕过了角色保护，AuthService
--     也会从菜单权限中剔除 admin:all，阻断"通过菜单取得 admin:all"的提权链路。
--
-- 幂等性：
--   - ALTER TABLE 添加列（重复执行需保证列不存在，由 Flyway schema_history 保证只运行一次）。
--   - 先 UPDATE 把已存在的系统预置角色标记为 is_system=1（覆盖 role_key='admin' 已存在
--     但未被标记的历史数据）；再 INSERT IGNORE 补齐缺失的"超级管理员"角色。
-- =============================================================================

ALTER TABLE sys_role
    ADD COLUMN is_system TINYINT NOT NULL DEFAULT 0
    COMMENT '系统保留角色标志（1=系统保留 0=普通角色）';

-- 系统保留角色清单：与 database/bootstrap 下各引导脚本预置的 role_key 保持一致。
-- 用 UPDATE 标记已存在但尚未设置 is_system 的历史记录（幂等，已标记的不受影响）。
UPDATE sys_role
SET is_system = 1
WHERE role_key IN ('admin', 'system_admin', 'knowledge_admin', 'ingest_admin', 'ai_chat_admin');

-- 若"超级管理员"角色（role_key='admin'）尚未存在，则插入一条并标记为系统保留。
-- INSERT IGNORE 保证幂等：重复迁移不会报错（role_key 唯一索引兜底）。
INSERT IGNORE INTO sys_role (role_name, role_key, role_sort, status, is_system, remark)
VALUES ('超级管理员', 'admin', 1, 1, 1, '系统保留角色：超级管理员默认角色，禁止非超级管理员修改/停用/删除');
