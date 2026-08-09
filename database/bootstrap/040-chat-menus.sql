-- AI Chat menu and permissions. Safe to run repeatedly.
USE docbase_iam;

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT 0, 'AI 对话', 1, 'AiChat', '/ai/chat', 'ai:chat:list', 0, 30, 1, 'AI 会话和历史消息'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'AiChat');

SET @chat_menu = (SELECT menu_id FROM sys_menu WHERE router_name = 'AiChat' LIMIT 1);

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @chat_menu, 'AI 对话列表', 3, '', '', 'ai:chat:list', 1, 31, 1, '会话管理权限'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ai:chat:list' AND is_button = 1);

INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark)
SELECT @chat_menu, 'AI 对话问答', 3, '', '', 'ai:chat:query', 1, 32, 1, '预留给后续流式问答'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'ai:chat:query');

INSERT IGNORE INTO sys_role (role_name, role_key, role_sort, status, remark)
SELECT 'AI 对话管理员', 'ai_chat_admin', 4, 1, '管理自身 AI 会话'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'ai_chat_admin');

SET @chat_role = (SELECT role_id FROM sys_role WHERE role_key = 'ai_chat_admin' LIMIT 1);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT @chat_role, menu_id FROM sys_menu WHERE menu_id = @chat_menu OR parent_id = @chat_menu;
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT user_id, @chat_role FROM sys_user WHERE is_admin = 1;
