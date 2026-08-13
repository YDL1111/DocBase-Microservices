-- =============================================================================
-- V9: AI Chat 对话菜单与权限种子（幂等）
--
-- 把 database/bootstrap/040-chat-menus.sql 迁入 Flyway。原因：原脚本挂载到
-- /docker-entrypoint-initdb.d，在 MySQL 首次启动、Flyway 建表之前执行，此时
-- sys_menu 等表尚未建立，全新数据卷会初始化失败。Flyway 迁移保证在表建立后执行。
--
-- 与原脚本的差异：
--   - 去掉 USE docbase_iam（Flyway 通过 datasource 已定位到库）。
--   - 去掉 SET @变量（H2 不支持会话变量），父菜单 ID 用标量子查询内联。
--   - 保留 INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS 幂等守卫。
--   - 显式标记 ai_chat_admin 为 is_system=1（V4 的 UPDATE 在角色创建之前运行，
--     无法标记此后由本迁移创建的角色）。
--
-- 菜单结构：
--   AI 对话（菜单，routerName=AiChat，sort 30）—— 注意：040 脚本把 AI 对话注册为
--        menu_type=1 的菜单节点（非目录），其下挂按钮权限。此处保留原结构。
--     └── 按钮：对话列表/对话问答 权限
--
-- 角色：ai_chat_admin（AI 对话管理员）关联上述全部菜单；is_admin=1 用户自动关联。
-- =============================================================================

-- =============================================================================
-- 1. 菜单项（幂等）
-- =============================================================================

-- AI 对话根节点（menu_type=1 菜单，可见）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, 'AI 对话', 1, 'AiChat', '/ai/chat', 'ai:chat:list', 0, 30, 1, 'AI 会话和历史消息'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'AiChat');

-- 对话列表权限（按钮）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'AiChat' LIMIT 1),
       'AI 对话列表', 3, '', '', 'ai:chat:list', 1, 31, 1, '会话管理权限'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ai:chat:list' AND is_button = 1);

-- 对话问答权限（按钮，预留给后续流式问答）
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'AiChat' LIMIT 1),
       'AI 对话问答', 3, '', '', 'ai:chat:query', 1, 32, 1, '预留给后续流式问答'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ai:chat:query');

-- =============================================================================
-- 2. 创建"AI 对话管理员"角色并关联全部 AI Chat 菜单（幂等）
-- =============================================================================

INSERT INTO sys_role (role_name, role_key, role_sort, status, remark)
SELECT 'AI 对话管理员', 'ai_chat_admin', 4, 1, '管理自身 AI 会话'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'ai_chat_admin');

-- 关联 AI 对话菜单到角色（幂等）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.parent_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'AiChat' LIMIT 1)
                  OR m.menu_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'AiChat' LIMIT 1)
WHERE r.role_key = 'ai_chat_admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

-- =============================================================================
-- 3. 为超级管理员用户（is_admin=1）关联 AI 对话管理员角色（幂等）
-- =============================================================================

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u
JOIN sys_role r ON r.role_key = 'ai_chat_admin'
WHERE u.is_admin = 1
  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

-- =============================================================================
-- 4. 幂等标记 ai_chat_admin 为系统保留角色
--
-- V4 的 UPDATE（WHERE role_key IN (...'ai_chat_admin')）在本迁移创建 ai_chat_admin
-- 之前已经运行，无法标记此后插入的角色。此处补做幂等标记，保证空库与升级库两条
-- 路径下 ai_chat_admin 均为 is_system=1。
-- =============================================================================

UPDATE sys_role
SET is_system = 1
WHERE role_key = 'ai_chat_admin'
  AND is_system = 0;
