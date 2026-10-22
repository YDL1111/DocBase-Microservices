CREATE TABLE sys_role_organization (
    role_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, organization_id)
) ENGINE=InnoDB;

INSERT INTO sys_menu(parent_id,menu_name,menu_type,router_name,path,permission,is_button,sort_num,status,is_system,remark)
SELECT menu_id,'分配人员角色',3,'','','system:user:assign-role',1,46,1,1,'独立控制人员角色分配'
FROM sys_menu WHERE router_name='SystemUser' AND deleted=0 LIMIT 1;
INSERT INTO sys_menu(parent_id,menu_name,menu_type,router_name,path,permission,is_button,sort_num,status,is_system,remark)
SELECT menu_id,'启停人员',3,'','','system:user:status',1,47,1,1,'独立控制人员启用和停用'
FROM sys_menu WHERE router_name='SystemUser' AND deleted=0 LIMIT 1;

-- Preserve historical capabilities; administrators can now remove either grant independently.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT old.role_id,new_menu.menu_id FROM sys_role_menu old
JOIN sys_menu old_menu ON old.menu_id=old_menu.menu_id
JOIN sys_menu new_menu ON new_menu.permission IN ('system:user:assign-role','system:user:status') AND new_menu.deleted=0
WHERE old_menu.permission IN ('system:user:update','system:user:edit') AND old_menu.deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=old.role_id AND existing.menu_id=new_menu.menu_id);

INSERT INTO sys_menu_owner_role(menu_id,role_id)
SELECT m.menu_id,r.role_id FROM sys_menu m JOIN sys_role r ON r.role_key='system_admin' AND r.deleted=0
WHERE m.permission IN ('system:user:assign-role','system:user:status') AND m.deleted=0;
