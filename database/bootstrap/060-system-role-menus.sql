-- =============================================================================
-- 系统管理 - 角色管理菜单与权限种子数据（幂等）
-- 为 docbase_iam.sys_menu 插入"系统管理 > 角色管理"相关菜单项，
-- 使前端动态路由能正确渲染角色管理页面。
--
-- 菜单结构：
--   系统管理（目录，menu_type=2，由 050 脚本创建）
--     └── 角色管理（菜单，routerName=SystemRole）
--     └── 各类按钮权限（menu_type=3）
--
-- 注意：
--   - 本脚本使用 INSERT IGNORE 确保幂等，可重复执行，不会破坏已有菜单/角色数据；
--   - 菜单必须通过 sys_role_menu 关联到角色，用户才能看到；
--   - 角色管理无独立详情/菜单授权路由（详情与菜单授权均用对话框）；
--   - 系统管理员角色（system_admin）由 050 脚本创建，此处仅追加新菜单的关联。
-- =============================================================================

USE docbase_iam;

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- 引用 050 脚本创建的"系统管理"根目录
SET @system_root = (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1);

-- 角色管理（menu_type=1 菜单，可见）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '角色管理', 1, 'SystemRole', '/system/role', 'system:role:list', 0, 46, 1, '角色管理列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemRole');

-- 角色管理按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '查看角色', 3, '', '', 'system:role:list', 1, 47, 1, '查看角色列表/详情/分配菜单权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:list' AND is_button = 1);

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '新建角色', 3, '', '', 'system:role:create', 1, 48, 1, '创建角色权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:create');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '编辑角色', 3, '', '', 'system:role:update', 1, 49, 1, '修改角色/启停状态/分配菜单权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:update');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '删除角色', 3, '', '', 'system:role:delete', 1, 50, 1, '删除角色权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:delete');

-- 查看菜单树（只读，用于角色菜单授权对话框的全量菜单树）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @system_root, '查看菜单树', 3, '', '', 'system:menu:list', 1, 51, 1, '查看菜单树（角色授权）权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:menu:list');

-- =============================================================================
-- 2. 将新菜单关联到系统管理员角色（幂等）
-- =============================================================================

SET @system_role = (SELECT role_id FROM sys_role WHERE role_key = 'system_admin' LIMIT 1);

-- 关联系统管理根目录下的全部菜单（含新增的角色管理菜单与按钮）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT @system_role, menu_id FROM sys_menu
WHERE menu_id = @system_root OR parent_id = @system_root;

-- =============================================================================
-- 3. 确保超级管理员用户（is_admin=1）仍关联系统管理员角色（幂等）
-- =============================================================================

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT user_id, @system_role FROM sys_user WHERE is_admin = 1;
