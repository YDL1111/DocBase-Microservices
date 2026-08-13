-- =============================================================================
-- V12: 菜单管理（SystemMenu）菜单与权限种子（幂等）
--
-- 背景：V6 只种入了"系统管理 > 用户管理/角色管理"及其按钮权限，以及
-- system:menu:list（查看菜单树）按钮；缺少"菜单管理"页面与
-- system:menu:create/update/delete 三个按钮，导致真实环境没有菜单管理入口
-- （前端 SystemMenu 组件已注册，但动态路由无菜单节点可渲染）。
--
-- 本迁移补齐：
--   - SystemMenu 页面（menu_type=1，routerName=SystemMenu，path=/system/menu）
--   - 新建菜单 / 编辑菜单 / 删除菜单 三个按钮（menu_type=3）
--   - 仅对上述四个新节点建立 system_admin 的 sys_role_menu 关联与
--     sys_menu_owner_role 管理归属（幂等守卫保证不重复）
--
-- 安全（防权限/归属扩张）：
--   - sys_role_menu 关联范围严格限定为本次新增的四个节点，绝不按
--     "SystemManage 根目录全部子节点"批量关联——避免把管理员此前主动撤销的
--     既有菜单授权重新写回；
--   - sys_menu_owner_role 同样只补四个新节点，绝不复制 system_admin 当前全部
--     sys_role_menu——避免覆盖 V10 之后人工完成的 owner 转让。
--
-- 幂等性：INSERT ... SELECT ... WHERE NOT EXISTS 守卫（与 V6 同模式，H2 兼容，
-- 不用会话变量，父菜单 ID 用标量子查询内联），可重复执行。
--
-- 安全：菜单与按钮直接以 is_system=1 写入，末尾再补幂等 UPDATE 标记，
-- 覆盖"旧数据已存在但未标记"的升级路径（与 V5/V6 末尾标记一致）。
-- =============================================================================

-- =============================================================================
-- 1. SystemMenu 页面（menu_type=1 菜单，可见）
-- =============================================================================
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark, is_system)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '菜单管理', 1, 'SystemMenu', '/system/menu', 'system:menu:list', 0, 52, 1, '菜单管理列表页', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemMenu' AND deleted = 0);

-- =============================================================================
-- 2. 菜单管理按钮权限（menu_type=3 按钮，parent=系统管理根目录，与 V6 结构一致）
-- =============================================================================

-- 新建菜单
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark, is_system)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '新建菜单', 3, '', '', 'system:menu:create', 1, 53, 1, '创建菜单权限', 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:menu:create' AND deleted = 0);

-- 编辑菜单
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark, is_system)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '编辑菜单', 3, '', '', 'system:menu:update', 1, 54, 1, '修改菜单/启停状态权限', 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:menu:update' AND deleted = 0);

-- 删除菜单
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark, is_system)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2 LIMIT 1),
       '删除菜单', 3, '', '', 'system:menu:delete', 1, 55, 1, '删除菜单权限', 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:menu:delete' AND deleted = 0);

-- =============================================================================
-- 3. 仅将本次新增的四个节点关联到 system_admin（幂等）
--
-- 重要：关联范围必须限定为本次迁移新插入的节点
-- （SystemMenu 页面 + system:menu:create/update/delete 三个按钮），
-- 不得按"SystemManage 根目录全部子节点"批量关联——否则会把管理员此前主动
-- 撤销的既有菜单授权重新写回 sys_role_menu（权限扩张 P0）。
-- =============================================================================

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0
  AND (m.router_name = 'SystemMenu'
       OR m.permission IN ('system:menu:create', 'system:menu:update', 'system:menu:delete'))
WHERE r.role_key = 'system_admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

-- =============================================================================
-- 4. 仅为新增的四个节点补齐管理归属（owner 表，幂等）
--
-- 重要：同样限定为本次新增节点，绝不把 system_admin 当前全部 sys_role_menu
-- 复制到 owner 表——否则会覆盖 V10 之后人工完成的 owner 转让（如
-- replaceOwners 删除原 owner 行后，V12 又把它写回），导致资源归属扩张 P0。
-- 只写 sys_menu_owner_role，不触碰 sys_role_menu / 不授予任何 permission。
-- =============================================================================

INSERT INTO sys_menu_owner_role (menu_id, role_id)
SELECT m.menu_id, r.role_id
FROM sys_menu m
JOIN sys_role r ON r.role_key = 'system_admin'
WHERE m.deleted = 0
  AND (m.router_name = 'SystemMenu'
       OR m.permission IN ('system:menu:create', 'system:menu:update', 'system:menu:delete'))
  AND NOT EXISTS (
      SELECT 1
      FROM sys_menu_owner_role mor
      WHERE mor.menu_id = m.menu_id
        AND mor.role_id = r.role_id
  );

-- =============================================================================
-- 5. 最终标记：确保本迁移插入的菜单 is_system=1（幂等，覆盖历史数据路径）
-- =============================================================================

UPDATE sys_menu
SET is_system = 1
WHERE deleted = 0
  AND router_name IN ('SystemManage', 'SystemUser', 'SystemRole', 'SystemMenu');

UPDATE sys_menu
SET is_system = 1
WHERE deleted = 0
  AND permission IN (
    'system:menu:list', 'system:menu:create', 'system:menu:update', 'system:menu:delete',
    'system:role:list', 'system:role:create', 'system:role:update', 'system:role:delete'
);
