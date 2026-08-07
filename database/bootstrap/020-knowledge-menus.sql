-- =============================================================================
-- Knowledge 知识库菜单与权限种子数据（幂等）
-- 为 docbase_iam.sys_menu 插入 Knowledge 相关菜单项，
-- 使前端动态路由能正确渲染 Knowledge 页面。
--
-- 菜单结构：
--   知识库（目录，menu_type=2）
--     ├── 知识库列表（菜单，routerName=KnowledgeList）
--     └── 各类按钮权限（menu_type=3）
--
-- 注意：
--   - 详情页（KnowledgeDetail）不显示在菜单中，通过列表页点击进入；
--   - 菜单必须通过 sys_role_menu 关联到角色，用户才能看到；
--   - 本脚本使用 INSERT IGNORE 确保幂等，可重复执行。
-- =============================================================================

USE docbase_iam;

-- =============================================================================
-- 1. 菜单项（幂等：通过唯一键 uk_sys_menu_router_name 或手动判断）
-- =============================================================================

-- 知识库根目录（menu_type=2 目录）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, '知识库', 2, 'Knowledge', '/knowledge', '', 0, 10, 1, '知识库根目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2);

SET @knowledge_root = (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1);

-- 知识库列表（menu_type=1 菜单，可见）
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '知识库列表', 1, 'KnowledgeList', '/knowledge', 'knowledge:base:list', 0, 10, 1, '知识库列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'KnowledgeList');

-- 注意：知识库详情页（KnowledgeDetail）不注册为可见菜单！
-- 它通过列表页点击进入，路径 /knowledge/:id 中的 :id 是动态参数。
-- 若注册为可见菜单，用户点击会导航到字面路径 /knowledge/:id，导致 Number(":id") → NaN。

-- 知识库管理按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '新建知识库', 3, '', '', 'knowledge:base:create', 1, 11, 1, '创建知识库权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:create');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '编辑知识库', 3, '', '', 'knowledge:base:update', 1, 12, 1, '更新知识库权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:update');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '删除知识库', 3, '', '', 'knowledge:base:delete', 1, 13, 1, '删除知识库权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:delete');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '知识库列表权限', 3, '', '', 'knowledge:base:list', 1, 14, 1, '查看知识库列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:list');

-- 成员管理按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '成员管理', 3, '', '', 'knowledge:member:manage', 1, 20, 1, '管理知识库成员权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:member:manage');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '成员列表', 3, '', '', 'knowledge:member:list', 1, 21, 1, '查看成员列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:member:list');

-- 目录管理按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '新建目录', 3, '', '', 'knowledge:folder:create', 1, 30, 1, '创建目录权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:create');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '编辑目录', 3, '', '', 'knowledge:folder:update', 1, 31, 1, '更新目录权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:update');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '删除目录', 3, '', '', 'knowledge:folder:delete', 1, 32, 1, '删除目录权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:delete');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '目录列表', 3, '', '', 'knowledge:folder:list', 1, 33, 1, '查看目录树权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:list');

-- 文档管理按钮权限
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '文档列表', 3, '', '', 'knowledge:document:list', 1, 40, 1, '查看文档列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:list');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '新建文档', 3, '', '', 'knowledge:document:create', 1, 41, 1, '创建文档权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:create');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '编辑文档', 3, '', '', 'knowledge:document:update', 1, 42, 1, '更新文档权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:update');

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @knowledge_root, '删除文档', 3, '', '', 'knowledge:document:delete', 1, 43, 1, '删除文档权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:delete');

-- =============================================================================
-- 2. 创建"知识库管理员"角色并关联所有 Knowledge 权限（幂等）
-- =============================================================================

INSERT IGNORE INTO sys_role (role_name, role_key, role_sort, status, remark)
VALUES ('知识库管理员', 'knowledge_admin', 2, 1, '知识库管理员，拥有所有 Knowledge 权限');

SET @knowledge_role = (SELECT role_id FROM sys_role WHERE role_key = 'knowledge_admin' LIMIT 1);

-- 关联 Knowledge 菜单到角色（幂等）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT @knowledge_role, menu_id FROM sys_menu
WHERE menu_id = @knowledge_root OR parent_id = @knowledge_root;

-- =============================================================================
-- 3. 为超级管理员用户（is_admin=1）关联知识库管理员角色
-- =============================================================================

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT user_id, @knowledge_role FROM sys_user WHERE is_admin = 1;
