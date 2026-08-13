-- =============================================================================
-- V8: Ingest 导入任务菜单与权限种子（幂等）
--
-- 把 database/bootstrap/030-ingest-menus.sql 迁入 Flyway。原因：原脚本挂载到
-- /docker-entrypoint-initdb.d，在 MySQL 首次启动、Flyway 建表之前执行，此时
-- sys_menu 等表尚未建立，全新数据卷会初始化失败。Flyway 迁移保证在表建立后执行。
--
-- 与原脚本的差异：
--   - 去掉 USE docbase_iam（Flyway 通过 datasource 已定位到库）。
--   - 去掉 SET @变量（H2 不支持会话变量），父菜单 ID 用标量子查询内联。
--   - 保留 INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS 幂等守卫。
--   - 显式标记 ingest_admin 为 is_system=1（V4 的 UPDATE 在角色创建之前运行，
--     无法标记此后由本迁移创建的角色）。
--
-- 菜单结构：
--   导入任务（目录，routerName=IngestTaskDir，sort 20）
--     └── 任务列表（菜单，routerName=IngestTask）
--     └── 按钮：任务列表/详情/重试/取消 权限
--
-- 角色：ingest_admin（导入任务管理员）关联上述全部菜单；is_admin=1 用户自动关联。
-- =============================================================================

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- 导入任务根目录（menu_type=2 目录）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, '导入任务', 2, 'IngestTaskDir', '/ingest', '', 0, 20, 1, '导入任务根目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2);

-- 任务列表（menu_type=1 菜单，可见）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1),
       '任务列表', 1, 'IngestTask', '/ingest/tasks', 'ingest:task:list', 0, 10, 1, '导入任务列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'IngestTask');

-- 任务列表权限（按钮）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1),
       '任务列表权限', 3, '', '', 'ingest:task:list', 1, 11, 1, '查看任务列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:list' AND is_button = 1);

-- 任务详情权限（按钮）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1),
       '任务详情权限', 3, '', '', 'ingest:task:view', 1, 12, 1, '查看任务详情权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:view');

-- 重试任务（按钮）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1),
       '重试任务', 3, '', '', 'ingest:task:retry', 1, 13, 1, '重试任务权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:retry');

-- 取消任务（按钮）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1),
       '取消任务', 3, '', '', 'ingest:task:cancel', 1, 14, 1, '取消任务权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ingest:task:cancel');

-- =============================================================================
-- 2. 创建"导入任务管理员"角色并关联全部 Ingest 菜单（幂等）
-- =============================================================================

INSERT INTO sys_role (role_name, role_key, role_sort, status, remark)
SELECT '导入任务管理员', 'ingest_admin', 3, 1, '导入任务管理员，拥有所有 Ingest 权限'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'ingest_admin');

-- 关联导入任务菜单到角色（幂等）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.parent_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1)
                  OR m.menu_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'IngestTaskDir' AND menu_type = 2 LIMIT 1)
WHERE r.role_key = 'ingest_admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

-- =============================================================================
-- 3. 为超级管理员用户（is_admin=1）关联导入任务管理员角色（幂等）
-- =============================================================================

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u
JOIN sys_role r ON r.role_key = 'ingest_admin'
WHERE u.is_admin = 1
  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

-- =============================================================================
-- 4. 幂等标记 ingest_admin 为系统保留角色
--
-- V4 的 UPDATE（WHERE role_key IN (...'ingest_admin',...)）在本迁移创建 ingest_admin
-- 之前已经运行，无法标记此后插入的角色。此处补做幂等标记，保证空库与升级库两条
-- 路径下 ingest_admin 均为 is_system=1。
-- =============================================================================

UPDATE sys_role
SET is_system = 1
WHERE role_key = 'ingest_admin'
  AND is_system = 0;
