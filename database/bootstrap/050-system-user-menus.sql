-- =============================================================================
-- 系统管理 - 用户管理菜单与权限种子数据（幂等）
-- 为 docbase_iam.sys_menu 插入"系统管理 > 用户管理"相关菜单项，
-- 使前端动态路由能正确渲染用户管理页面。
--
-- 菜单结构：
--   系统管理（目录，menu_type=2）
--     └── 用户管理（菜单，routerName=SystemUser）
--     └── 各类按钮权限（menu_type=3）
--
-- 注意：
--   - 本脚本使用 INSERT IGNORE 确保幂等，可重复执行，不会破坏已有菜单/角色数据；
--   - 菜单必须通过 sys_role_menu 关联到角色，用户才能看到；
--   - 用户管理无详情路由（详情用对话框，无需注册为可见菜单）。
-- =============================================================================

USE docbase_iam;

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- 系统管理根目录（menu_type=2 目录）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, '系统管理', 2, 'SystemManage', '/system', '', 0, 40, 1, '系统管理根目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2);

SET @system_root = (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1);

-- 用户管理（menu_type=1 菜单，可见）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '用户管理', 1, 'SystemUser', '/system/user', 'system:user:list', 0, 40, 1, '用户管理列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemUser');

-- 用户管理按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '查看用户', 3, '', '', 'system:user:list', 1, 41, 1, '查看用户列表/详情/角色权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:list' AND is_button = 1);

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '新建用户', 3, '', '', 'system:user:create', 1, 42, 1, '创建用户权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:create');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '编辑用户', 3, '', '', 'system:user:update', 1, 43, 1, '修改用户/启停状态权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:update');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '删除用户', 3, '', '', 'system:user:delete', 1, 44, 1, '删除用户权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:delete');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '重置密码', 3, '', '', 'system:user:reset-password', 1, 45, 1, '重置用户密码权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:reset-password');

-- =============================================================================
-- 2. 创建"系统管理员"角色并关联所有系统管理权限（幂等）
-- =============================================================================

INSERT IGNORE INTO sys_role (role_name, role_key, role_sort, status, remark)
VALUES ('系统管理员', 'system_admin', 5, 1, '系统管理员，拥有用户管理全部权限');

SET @system_role = (SELECT role_id FROM sys_role WHERE role_key = 'system_admin' LIMIT 1);

-- 关联系统管理菜单到角色（幂等）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT @system_role, menu_id FROM sys_menu
WHERE menu_id = @system_root OR parent_id = @system_root;

-- =============================================================================
-- 3. 为超级管理员用户（is_admin=1）关联系统管理员角色
-- =============================================================================

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT user_id, @system_role FROM sys_user WHERE is_admin = 1;
