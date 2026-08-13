-- =============================================================================
-- V7: Knowledge 知识库菜单与权限种子（幂等）
--
-- 把 database/bootstrap/020-knowledge-menus.sql 迁入 Flyway。原因：原脚本挂载到
-- /docker-entrypoint-initdb.d，在 MySQL 首次启动、Flyway 建表之前执行，此时
-- sys_menu 等表尚未建立，全新数据卷会初始化失败。Flyway 迁移保证在表建立后执行。
--
-- 与原脚本的差异：
--   - 去掉 USE docbase_iam（Flyway 通过 datasource 已定位到库）。
--   - 去掉 SET @变量（H2 不支持会话变量），父菜单 ID 用标量子查询内联。
--   - 保留 INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS 幂等守卫。
--   - 显式标记 knowledge_admin 为 is_system=1（V4 的 UPDATE 在角色创建之前运行，
--     无法标记此后由本迁移创建的角色；见 V4__role_is_system.sql 注释）。
--
-- 菜单结构：
--   知识库（目录，routerName=Knowledge，sort 10）
--     ├── 知识库列表（菜单，routerName=KnowledgeList）
--     └── 按钮：知识库/成员/目录/文档 管理权限
--
-- 角色：knowledge_admin（知识库管理员）关联上述全部菜单；is_admin=1 用户自动关联。
-- =============================================================================

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- 知识库根目录（menu_type=2 目录）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, '知识库', 2, 'Knowledge', '/knowledge', '', 0, 10, 1, '知识库根目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2);

-- 知识库列表（menu_type=1 菜单，可见）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '知识库列表', 1, 'KnowledgeList', '/knowledge', 'knowledge:base:list', 0, 10, 1, '知识库列表页'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'KnowledgeList');

-- 知识库管理按钮权限
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '新建知识库', 3, '', '', 'knowledge:base:create', 1, 11, 1, '创建知识库权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:create');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '编辑知识库', 3, '', '', 'knowledge:base:update', 1, 12, 1, '更新知识库权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:update');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '删除知识库', 3, '', '', 'knowledge:base:delete', 1, 13, 1, '删除知识库权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:delete');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '知识库列表权限', 3, '', '', 'knowledge:base:list', 1, 14, 1, '查看知识库列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:base:list' AND is_button = 1);

-- 成员管理按钮权限
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '成员管理', 3, '', '', 'knowledge:member:manage', 1, 20, 1, '管理知识库成员权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:member:manage');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '成员列表', 3, '', '', 'knowledge:member:list', 1, 21, 1, '查看成员列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:member:list');

-- 目录管理按钮权限
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '新建目录', 3, '', '', 'knowledge:folder:create', 1, 30, 1, '创建目录权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:create');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '编辑目录', 3, '', '', 'knowledge:folder:update', 1, 31, 1, '更新目录权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:update');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '删除目录', 3, '', '', 'knowledge:folder:delete', 1, 32, 1, '删除目录权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:delete');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '目录列表', 3, '', '', 'knowledge:folder:list', 1, 33, 1, '查看目录树权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:folder:list');

-- 文档管理按钮权限
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '文档列表', 3, '', '', 'knowledge:document:list', 1, 40, 1, '查看文档列表权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:list');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '新建文档', 3, '', '', 'knowledge:document:create', 1, 41, 1, '创建文档权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:create');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '编辑文档', 3, '', '', 'knowledge:document:update', 1, 42, 1, '更新文档权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:update');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1),
       '删除文档', 3, '', '', 'knowledge:document:delete', 1, 43, 1, '删除文档权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'knowledge:document:delete');

-- =============================================================================
-- 2. 创建"知识库管理员"角色并关联全部知识库菜单（幂等）
-- =============================================================================

INSERT INTO sys_role (role_name, role_key, role_sort, status, remark)
SELECT '知识库管理员', 'knowledge_admin', 2, 1, '知识库管理员，拥有所有 Knowledge 权限'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'knowledge_admin');

-- 关联知识库菜单到角色（幂等）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.parent_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1)
                  OR m.menu_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'Knowledge' AND menu_type = 2 LIMIT 1)
WHERE r.role_key = 'knowledge_admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

-- =============================================================================
-- 3. 为超级管理员用户（is_admin=1）关联知识库管理员角色（幂等）
-- =============================================================================

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u
JOIN sys_role r ON r.role_key = 'knowledge_admin'
WHERE u.is_admin = 1
  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

-- =============================================================================
-- 4. 幂等标记 knowledge_admin 为系统保留角色
--
-- V4 的 UPDATE（WHERE role_key IN ('admin','system_admin','knowledge_admin',...)）在本迁移
-- 创建 knowledge_admin 之前已经运行，无法标记此后插入的角色。此处补做幂等标记，
-- 保证空库与升级库两条路径下 knowledge_admin 均为 is_system=1。
-- =============================================================================

UPDATE sys_role
SET is_system = 1
WHERE role_key = 'knowledge_admin'
  AND is_system = 0;
