-- Organization hierarchy, user organization membership, registration role and system menu seeds.
CREATE TABLE sys_organization (
    organization_id   BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id         BIGINT       NOT NULL DEFAULT 0,
    organization_name VARCHAR(128) NOT NULL,
    organization_code VARCHAR(64)  NOT NULL,
    sort_num          INT          NOT NULL DEFAULT 0,
    status            TINYINT      NOT NULL DEFAULT 1,
    is_system         TINYINT      NOT NULL DEFAULT 0,
    remark            VARCHAR(512) NOT NULL DEFAULT '',
    creator_id        BIGINT                NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater_id        BIGINT                NULL,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id),
    UNIQUE KEY uk_sys_organization_code (organization_code),
    KEY idx_sys_organization_parent (parent_id),
    KEY idx_sys_organization_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE sys_user ADD COLUMN organization_id BIGINT NULL AFTER user_id;
CREATE INDEX idx_sys_user_organization ON sys_user (organization_id);

-- Stable sample organizations. They contain no credentials and are safe in every environment.
INSERT INTO sys_organization (parent_id, organization_name, organization_code, sort_num, status, is_system, remark)
SELECT 0, 'DocBase 总部', 'docbase_hq', 10, 1, 1, '系统预置组织根节点'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_organization WHERE organization_code = 'docbase_hq');

INSERT INTO sys_organization (parent_id, organization_name, organization_code, sort_num, status, is_system, remark)
SELECT (SELECT organization_id FROM sys_organization WHERE organization_code = 'docbase_hq' LIMIT 1),
       '研发中心', 'docbase_rd', 20, 1, 0, '部门可见性测试组织'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_organization WHERE organization_code = 'docbase_rd');

INSERT INTO sys_organization (parent_id, organization_name, organization_code, sort_num, status, is_system, remark)
SELECT (SELECT organization_id FROM sys_organization WHERE organization_code = 'docbase_hq' LIMIT 1),
       '运营中心', 'docbase_ops', 30, 1, 0, '跨部门隔离测试组织'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_organization WHERE organization_code = 'docbase_ops');

-- Organization management menu and button permissions.
INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, is_system, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' LIMIT 1),
       '组织管理', 1, 'SystemOrganization', '/system/organizations', 'system:org:list', 0, 40, 1, 1, '组织树管理'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'SystemOrganization');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, is_system, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemOrganization' LIMIT 1),
       '查看组织', 3, '', '', 'system:org:list', 1, 41, 1, 1, '查看组织树'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:org:list' AND is_button = 1);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, is_system, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemOrganization' LIMIT 1),
       '新建组织', 3, '', '', 'system:org:create', 1, 42, 1, 1, '新建组织节点'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:org:create');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, is_system, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemOrganization' LIMIT 1),
       '编辑组织', 3, '', '', 'system:org:update', 1, 43, 1, 1, '编辑或启停组织'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:org:update');

INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, is_system, remark)
SELECT (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemOrganization' LIMIT 1),
       '删除组织', 3, '', '', 'system:org:delete', 1, 44, 1, 1, '删除空组织节点'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:org:delete');

-- Existing system administrators receive the new organization management surface.
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id FROM sys_role r JOIN sys_menu m
  ON m.router_name = 'SystemOrganization' OR m.parent_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemOrganization' LIMIT 1)
WHERE r.role_key = 'system_admin' AND r.deleted = 0 AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);
INSERT INTO sys_menu_owner_role (menu_id, role_id)
SELECT m.menu_id, r.role_id FROM sys_role r JOIN sys_menu m
  ON m.router_name = 'SystemOrganization' OR m.parent_id = (SELECT menu_id FROM sys_menu WHERE router_name = 'SystemOrganization' LIMIT 1)
WHERE r.role_key = 'system_admin' AND r.deleted = 0 AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu_owner_role mor WHERE mor.role_id = r.role_id AND mor.menu_id = m.menu_id);

-- Safe default role for self-registered accounts. It grants basic knowledge reading and chat only.
INSERT INTO sys_role (role_name, role_key, role_sort, data_scope, status, is_system, remark)
SELECT '基础用户', 'registered_user', 50, 5, 1, 1, '开放注册账号的固定最小权限角色'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'registered_user');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id FROM sys_role r JOIN sys_menu m ON
       m.router_name IN ('Knowledge', 'KnowledgeList', 'AiChat')
       OR m.permission IN ('knowledge:base:list', 'knowledge:folder:list', 'knowledge:document:list', 'ai:chat:list', 'ai:chat:query')
WHERE r.role_key = 'registered_user' AND m.deleted = 0 AND m.status = 1
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);
