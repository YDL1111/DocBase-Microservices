-- =============================================================================
-- Ingest 导入任务菜单与权限种子数据（幂等）
-- 为 docbase_iam.sys_menu 插入 Ingest 相关菜单项。
--
-- 菜单结构：
--   导入任务（目录，menu_type=2）
--     └── 任务列表（菜单，routerName=IngestTask）
--     └── 各类按钮权限（menu_type=3）
--
-- 注意：
--   - 详情页（IngestTaskDetail）不注册为可见菜单，通过列表点击进入；
--   - 使用 INSERT IGNORE + WHERE NOT EXISTS 确保幂等；
--   - 为超级管理员（is_admin=1）关联角色。
-- =============================================================================

USE docbase_iam;

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- 导入任务根目录（menu_type=2 目录）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, '导入任务', 2, 'IngestTaskDir', '/ingest', '', 0, 20, 1, '导入任务根目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2);

SET @ingest_root = (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1);

-- 任务列表（menu_type=1 菜单，可见）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @ingest_root, '任务列表', 1, 'IngestTask', '/ingest/tasks', 'ingest:task:list', 0, 10, 1, '导入任务列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'IngestTask');

-- 注意：任务详情页（IngestTaskDetail）不注册为可见菜单！
-- 它通过列表页点击进入，路径 /ingest/tasks/:taskId 中的 :taskId 是动态参数。

-- 按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @ingest_root, '任务列表权限', 3, '', '', 'ingest:task:list', 1, 11, 1, '查看任务列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:list');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @ingest_root, '任务详情权限', 3, '', '', 'ingest:task:view', 1, 12, 1, '查看任务详情权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:view');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @ingest_root, '重试任务', 3, '', '', 'ingest:task:retry', 1, 13, 1, '重试任务权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:retry');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @ingest_root, '取消任务', 3, '', '', 'ingest:task:cancel', 1, 14, 1, '取消任务权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:cancel');

-- =============================================================================
-- 2. 创建"导入任务管理员"角色并关联权限（幂等）
-- =============================================================================

INSERT IGNORE INTO sys_role (role_name, role_key, role_sort, status, remark)
VALUES ('导入任务管理员', 'ingest_admin', 3, 1, '导入任务管理员，拥有所有 Ingest 权限');

SET @ingest_role = (SELECT role_id FROM sys_role WHERE role_key = 'ingest_admin' LIMIT 1);

-- 关联 Ingest 菜单到角色（幂等）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT @ingest_role, menu_id FROM sys_menu
WHERE menu_id = @ingest_root OR parent_id = @ingest_root;

-- =============================================================================
-- 3. 为超级管理员用户（is_admin=1）关联角色
-- =============================================================================

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT user_id, @ingest_role FROM sys_user WHERE is_admin = 1;
