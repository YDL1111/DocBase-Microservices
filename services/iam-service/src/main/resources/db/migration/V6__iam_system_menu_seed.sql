-- =============================================================================
-- V6: IAM 系统管理菜单与权限种子（幂等）
--
-- 把 050-system-user-menus.sql 与 060-system-role-menus.sql 中 IAM 系统管理的
-- 菜单/角色/关联种子迁移到 Flyway。原因：原脚本位于 database/bootstrap/*.sql，
-- 挂载到 /docker-entrypoint-initdb.d，在 MySQL 首次启动时、Flyway 建表之前执行，
-- 此时 sys_menu 等表尚未建立，存在时序缺陷。Flyway 迁移保证在表建立后执行。
--
-- 与原脚本的差异：
--   - 去掉 USE docbase_iam（Flyway 通过 datasource 已定位到库）。
--   - 去掉 SET @变量（H2 不支持会话变量），父菜单 ID 用标量子查询内联。
--   - 保留 INSERT ... SELECT ... WHERE NOT EXISTS 幂等守卫，可重复执行。
--   - 角色名更新为"系统管理员，拥有用户管理与角色管理全部权限"以反映当前范围。
--
-- 菜单结构：
--   系统管理（目录，routerName=SystemManage，sort 40）
--     ├── 用户管理（菜单，routerName=SystemUser，sort 40）
--     │     └── 按钮：查看用户/新建用户/编辑用户/删除用户/重置密码
--     ├── 角色管理（菜单，routerName=SystemRole，sort 46）
--     │     └── 按钮：查看角色/新建角色/编辑角色/删除角色/查看菜单树
--
-- 角色：system_admin（系统管理员）关联上述全部菜单；is_admin=1 用户自动关联。
-- =============================================================================

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- 系统管理根目录（menu_type=2 目录）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, '系统管理', 2, 'SystemManage', '/system', '', 0, 40, 1, '系统管理根目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2);

-- 用户管理（menu_type=1 菜单，可见）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '用户管理', 1, 'SystemUser', '/system/user', 'system:user:list', 0, 40, 1, '用户管理列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemUser');

-- 用户管理按钮权限
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '查看用户', 3, '', '', 'system:user:list', 1, 41, 1, '查看用户列表/详情/角色权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:list' AND is_button = 1);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '新建用户', 3, '', '', 'system:user:create', 1, 42, 1, '创建用户权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:create');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '编辑用户', 3, '', '', 'system:user:update', 1, 43, 1, '修改用户/启停状态权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:update');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '删除用户', 3, '', '', 'system:user:delete', 1, 44, 1, '删除用户权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:delete');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '重置密码', 3, '', '', 'system:user:reset-password', 1, 45, 1, '重置用户密码权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:user:reset-password');

-- 角色管理（menu_type=1 菜单，可见）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '角色管理', 1, 'SystemRole', '/system/role', 'system:role:list', 0, 46, 1, '角色管理列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemRole');

-- 角色管理按钮权限
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '查看角色', 3, '', '', 'system:role:list', 1, 47, 1, '查看角色列表/详情/分配菜单权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:list' AND is_button = 1);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '新建角色', 3, '', '', 'system:role:create', 1, 48, 1, '创建角色权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:create');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '编辑角色', 3, '', '', 'system:role:update', 1, 49, 1, '修改角色/启停状态/分配菜单权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:update');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '删除角色', 3, '', '', 'system:role:delete', 1, 50, 1, '删除角色权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:role:delete');

-- 查看菜单树（只读，用于角色菜单授权对话框的全量菜单树）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '查看菜单树', 3, '', '', 'system:menu:list', 1, 51, 1, '查看菜单树（角色授权）权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:menu:list');

-- =============================================================================
-- 2. 创建"系统管理员"角色并关联全部系统管理菜单（幂等）
-- =============================================================================

INSERT INTO sys_role (role_name, role_key, role_sort, status, is_system, remark)
SELECT '系统管理员', 'system_admin', 5, 1, 1, '系统管理员，拥有用户管理与角色管理全部权限'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'system_admin');

-- 关联系统管理根目录下的全部菜单（含用户管理、角色管理与按钮）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.parent_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1)
                  OR m.menu_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1)
WHERE r.role_key = 'system_admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

-- =============================================================================
-- 3. 确保超级管理员用户（is_admin=1）关联系统管理员角色（幂等）
-- =============================================================================

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u
JOIN sys_role r ON r.role_key = 'system_admin'
WHERE u.is_admin = 1
  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

-- =============================================================================
-- 4. 最终标记：确保本迁移插入的系统菜单 is_system=1
--
-- V5 的 UPDATE 只作用于迁移前已存在的行（引导脚本预置的历史数据）。在空库场景下，
-- 本迁移（V6）首次插入的 SystemManage/SystemUser/SystemRole 与 system:menu:list 等
-- 权限按钮位于 V5 之后，不会被 V5 的 UPDATE 覆盖。此处补做同一套幂等标记，
-- 保证无论空库还是升级库，V6 执行完毕后所有系统保留菜单 is_system=1。
-- =============================================================================

UPDATE sys_menu
SET is_system = 1
WHERE router_name IN ('SystemManage', 'SystemUser', 'SystemRole');

UPDATE sys_menu
SET is_system = 1
WHERE permission IN (
    'system:menu:list', 'system:menu:create', 'system:menu:update', 'system:menu:delete',
    'system:role:list', 'system:role:create', 'system:role:update', 'system:role:delete'
);

-- =============================================================================
-- 5. 幂等标记 system_admin 为系统保留角色
--
-- V4 的 UPDATE（WHERE role_key IN (...'system_admin',...)）在本迁移创建 system_admin
-- 之前已经运行，无法标记此后插入的角色。虽然本迁移的 INSERT 已显式写入 is_system=1，
-- 此处再补一条幂等 UPDATE，覆盖"先以旧版迁移（无 is_system 列）创建、后续升级"的
-- 历史路径，保证空库与升级库两条路径下 system_admin 均为 is_system=1。
-- =============================================================================

UPDATE sys_role
SET is_system = 1
WHERE role_key = 'system_admin'
  AND is_system = 0;
