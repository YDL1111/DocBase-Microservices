-- =============================================================================
-- V10: 菜单所有者角色归属表（资源归属与权限授权解耦）
--
-- 背景：此前菜单的"资源归属"与"权限授权"共用 sys_role_menu 一张表。create() 把
-- 调用者在父节点的角色关联复制到新菜单的 sys_role_menu，导致继承角色的所有成员
-- 都被动获得新菜单的 permission（权限扩散 P0）。例如：
--   - 调用者通过角色 B 拥有 knowledge:x，同时角色 A 关联父目录；
--   - 创建 knowledge:x 菜单后会把 A 关联到 sys_role_menu，
--     使 A 的其他成员越权获得 knowledge:x。
--
-- 修复：引入独立的 sys_menu_owner_role 表达"资源归属"，与 sys_role_menu 严格分开：
--   - sys_role_menu：菜单可见性 + permission 授权，直接参与用户权限集计算，
--     必须通过角色授权接口显式变更，绝不因"归属继承"写入。
--   - sys_menu_owner_role：资源归属，仅用于 assertOwnsMenuViaRole 等资源级授权
--     校验，不参与权限集计算，不会因写入而给角色成员新增 permission。
--
-- 回填策略（兼容性）：升级前 sys_role_menu 的每一条关联都隐含着"该角色能管理该菜单"
-- 的能力。为不破坏升级后普通管理员对历史菜单的管理能力，V10 把这些关联复制到
-- 归属表。这不会授予任何新 permission（sys_role_menu 本身不变），只是把已有的
-- 菜单管理能力转成独立归属表达。空库无历史数据，回填不产生行。
--
-- 幂等性：
--   - 建表由 Flyway schema_history 保证只运行一次。
--   - 回填使用 WHERE NOT EXISTS 守卫，重复执行不产生重复行（防御性，Flyway 本身
--     不会重复运行已记录的迁移）。
-- =============================================================================

CREATE TABLE sys_menu_owner_role (
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    role_id BIGINT NOT NULL COMMENT '拥有该菜单管理权的角色ID',
    PRIMARY KEY (menu_id, role_id)
) COMMENT='菜单所有者角色归属（资源归属，不参与权限计算）';

CREATE INDEX idx_mor_role_id ON sys_menu_owner_role(role_id);

-- 回填：把升级前已有的"角色→菜单"管理关系复制到归属表，保持升级后管理能力不变。
-- 仅复制关联到未删除菜单且未删除角色的行；NOT EXISTS 守卫保证幂等。
INSERT INTO sys_menu_owner_role (menu_id, role_id)
SELECT rm.menu_id, rm.role_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.menu_id = rm.menu_id AND m.deleted = 0
JOIN sys_role r ON r.role_id = rm.role_id AND r.deleted = 0
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_menu_owner_role mor
    WHERE mor.menu_id = rm.menu_id
      AND mor.role_id = rm.role_id
);
