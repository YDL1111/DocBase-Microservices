-- =============================================================================
-- V2: IAM RBAC schema for docbase_iam
-- Tables: sys_user, sys_role, sys_menu, sys_user_role, sys_role_menu
-- Design notes:
--   - Permission strings live in sys_menu.permission (no separate permission table),
--     matching the old project's semantics.
--   - Users and roles are many-to-many via sys_user_role (the old project stored a
--     single role_id on sys_user; the new model is more flexible while keeping the
--     same logical access path).
--   - Soft delete via deleted flag (0 = active, 1 = deleted).
--   - Status: 1 = enabled, 0 = disabled (for user and role).
-- =============================================================================

CREATE TABLE sys_user (
    user_id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username     VARCHAR(64)  NOT NULL COMMENT '用户账号',
    nickname     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户昵称',
    password     VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码 Hash',
    email        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户邮箱',
    phone_number VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '手机号码',
    sex          TINYINT      NOT NULL DEFAULT 0 COMMENT '性别（0未知 1男 2女）',
    avatar       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像地址',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '帐号状态（1正常 0停用）',
    is_admin     TINYINT      NOT NULL DEFAULT 0 COMMENT '超级管理员标志（1是 0否）',
    login_ip     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '最后登录IP',
    login_date   DATETIME              NULL COMMENT '最后登录时间',
    remark       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '备注',
    creator_id   BIGINT                NULL COMMENT '创建者ID',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater_id   BIGINT                NULL COMMENT '更新者ID',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标志（0存在 1删除）',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_status (status),
    KEY idx_sys_user_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户信息表';

CREATE TABLE sys_role (
    role_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
    role_key    VARCHAR(128) NOT NULL COMMENT '角色权限字符串',
    role_sort   INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    data_scope  TINYINT      NOT NULL DEFAULT 1 COMMENT '数据范围（1全部 2自定义 3本部门 4本部门及以下 5本人）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '角色状态（1正常 0停用）',
    remark      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '备注',
    creator_id  BIGINT                NULL COMMENT '创建者ID',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater_id  BIGINT                NULL COMMENT '更新者ID',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标志（0存在 1删除）',
    PRIMARY KEY (role_id),
    UNIQUE KEY uk_sys_role_role_key (role_key),
    KEY idx_sys_role_status (status),
    KEY idx_sys_role_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色信息表';

CREATE TABLE sys_menu (
    menu_id     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    parent_id   BIGINT        NOT NULL DEFAULT 0 COMMENT '父菜单ID',
    menu_name   VARCHAR(64)   NOT NULL COMMENT '菜单名称',
    menu_type   TINYINT       NOT NULL DEFAULT 1 COMMENT '类型（1菜单 2目录 3按钮）',
    router_name VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '路由名称',
    path        VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '组件路径',
    permission  VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '权限标识',
    meta_info   VARCHAR(1024) NOT NULL DEFAULT '{}' COMMENT '路由元信息',
    is_button   TINYINT       NOT NULL DEFAULT 0 COMMENT '是否按钮（1是 0否）',
    sort_num    INT           NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态（1启用 0停用）',
    remark      VARCHAR(512)  NOT NULL DEFAULT '' COMMENT '备注',
    creator_id  BIGINT                 NULL COMMENT '创建者ID',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater_id  BIGINT                 NULL COMMENT '更新者ID',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标志（0存在 1删除）',
    PRIMARY KEY (menu_id),
    KEY idx_sys_menu_parent_id (parent_id),
    KEY idx_sys_menu_status (status),
    KEY idx_sys_menu_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单权限表';

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role_id (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户角色关联表';

CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (role_id, menu_id),
    KEY idx_sys_role_menu_menu_id (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色菜单关联表';
