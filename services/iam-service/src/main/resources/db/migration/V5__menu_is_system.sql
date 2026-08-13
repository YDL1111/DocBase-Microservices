-- =============================================================================
-- V5: 菜单"系统保留"标记
--
-- 目的：为 sys_menu 增加 is_system 列，标记系统管理相关的安全关键菜单。
-- 系统保留菜单禁止非超级管理员修改、停用；任何人都不可删除。
--
-- is_system 比 menuName 更可靠：menuName 可能被本地化或重命名，而 is_system
-- 是数据库层面的不可变标记，仅由迁移脚本写入，API 创建的菜单一律 is_system=0。
--
-- 安全语义：
--   - is_system=1 的菜单：仅超级管理员（is_admin=1）可修改/停用，任何人都不可删除。
--   - API 创建的菜单：is_system 强制为 0，普通管理员无法伪造系统保留菜单。
--
-- 受保护对象（系统管理安全关键）：
--   - 目录/页面：SystemManage、SystemUser、SystemRole
--   - 权限码：system:menu:list/create/update/delete、system:role:list/create/update/delete
--
-- 注意：Knowledge/Ingest/AiChat 等业务菜单不标记 is_system，可由普通管理员管理。
--
-- 幂等性：
--   - ALTER TABLE 添加列由 Flyway schema_history 保证只运行一次。
--   - UPDATE 标记已存在的系统菜单（幂等，已标记的不受影响）。
-- =============================================================================

ALTER TABLE sys_menu
    ADD COLUMN is_system TINYINT NOT NULL DEFAULT 0
    COMMENT '系统保留菜单标志（1=系统保留 0=普通菜单）';

CREATE INDEX idx_sys_menu_is_system ON sys_menu(is_system);

-- 标记系统管理目录与页面（按 router_name，幂等）。
UPDATE sys_menu
SET is_system = 1
WHERE router_name IN ('SystemManage', 'SystemUser', 'SystemRole');

-- 标记系统管理与角色管理的按钮权限（按 permission，幂等）。
UPDATE sys_menu
SET is_system = 1
WHERE permission IN (
    'system:menu:list', 'system:menu:create', 'system:menu:update', 'system:menu:delete',
    'system:role:list', 'system:role:create', 'system:role:update', 'system:role:delete'
);
