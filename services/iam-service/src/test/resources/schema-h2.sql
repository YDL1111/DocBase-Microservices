-- H2-compatible IAM schema for tests (MySQL mode)
CREATE TABLE IF NOT EXISTS sys_user (
    user_id      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    nickname     VARCHAR(64)  NOT NULL DEFAULT '',
    password     VARCHAR(128) NOT NULL,
    email        VARCHAR(128) NOT NULL DEFAULT '',
    phone_number VARCHAR(32)  NOT NULL DEFAULT '',
    sex          TINYINT      NOT NULL DEFAULT 0,
    avatar       VARCHAR(512) NOT NULL DEFAULT '',
    status       TINYINT      NOT NULL DEFAULT 1,
    is_admin     TINYINT      NOT NULL DEFAULT 0,
    login_ip     VARCHAR(128) NOT NULL DEFAULT '',
    login_date   TIMESTAMP             ,
    remark       VARCHAR(512) NOT NULL DEFAULT '',
    creator_id   BIGINT                ,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater_id   BIGINT                ,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_username ON sys_user(username);

CREATE TABLE IF NOT EXISTS sys_role (
    role_id     BIGINT       AUTO_INCREMENT PRIMARY KEY,
    role_name   VARCHAR(64)  NOT NULL,
    role_key    VARCHAR(128) NOT NULL,
    role_sort   INT          NOT NULL DEFAULT 0,
    data_scope  TINYINT      NOT NULL DEFAULT 1,
    status      TINYINT      NOT NULL DEFAULT 1,
    remark      VARCHAR(512) NOT NULL DEFAULT '',
    creator_id  BIGINT                ,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater_id  BIGINT                ,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_role_key ON sys_role(role_key);

CREATE TABLE IF NOT EXISTS sys_menu (
    menu_id     BIGINT        AUTO_INCREMENT PRIMARY KEY,
    parent_id   BIGINT        NOT NULL DEFAULT 0,
    menu_name   VARCHAR(64)   NOT NULL,
    menu_type   TINYINT       NOT NULL DEFAULT 1,
    router_name VARCHAR(128)  NOT NULL DEFAULT '',
    path        VARCHAR(255)  NOT NULL DEFAULT '',
    permission  VARCHAR(128)  NOT NULL DEFAULT '',
    meta_info   VARCHAR(1024) NOT NULL DEFAULT '{}',
    is_button   TINYINT       NOT NULL DEFAULT 0,
    sort_num    INT           NOT NULL DEFAULT 0,
    status      TINYINT       NOT NULL DEFAULT 1,
    remark      VARCHAR(512)  NOT NULL DEFAULT '',
    creator_id  BIGINT                 ,
    create_time TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater_id  BIGINT                 ,
    update_time TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);

-- 超级管理员变更的数据库级全局互斥锁（单行守卫记录）。
-- 与生产 Flyway 迁移 V3__admin_mutex.sql 对应，使集成测试能在 H2 上验证
-- 跨实例串行化的竞争逻辑：事务内对守卫行的 UPDATE 获取行锁并持有到提交，
-- 并发事务在该行上阻塞等待（无需应用层租约）。
CREATE TABLE IF NOT EXISTS sys_admin_mutex (
    id           TINYINT NOT NULL PRIMARY KEY,
    lock_version INT     NOT NULL DEFAULT 0
);
MERGE INTO sys_admin_mutex(id, lock_version) KEY(id) VALUES (1, 0);
