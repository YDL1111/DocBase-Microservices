-- =============================================================================
-- V2: Knowledge service core schema for docbase_knowledge
-- Tables: knowledge_base, knowledge_member, knowledge_folder, knowledge_document,
--         knowledge_document_version, knowledge_document_acl
-- Design notes:
--   - No cross-schema foreign keys
--   - Soft delete via deleted flag (0 = active, 1 = deleted)
--   - Unique constraints include deleted to allow reuse of names after deletion
--   - High-performance indexes on knowledge_base_id, folder_id, owner_id, user_id, status, deleted
--   - Compatible with MySQL 8.4
-- =============================================================================

CREATE TABLE knowledge_base (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID',
    name            VARCHAR(128) NOT NULL COMMENT '知识库名称',
    description     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '知识库描述',
    owner_id        BIGINT       NOT NULL COMMENT '所有者用户ID(IAM)',
    visibility      TINYINT      NOT NULL DEFAULT 1 COMMENT '可见范围 1私有 2部门 3公开',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    sort_num        INT          NOT NULL DEFAULT 0 COMMENT '排序值',
    created_by      BIGINT       NOT NULL COMMENT '创建者用户ID(IAM)',
    updated_by      BIGINT                NULL COMMENT '更新者用户ID(IAM)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_base_name_deleted (name, deleted),
    KEY idx_knowledge_base_owner_id (owner_id),
    KEY idx_knowledge_base_status (status),
    KEY idx_knowledge_base_deleted (deleted),
    KEY idx_knowledge_base_created_by (created_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库主表';

CREATE TABLE knowledge_member (
    id               BIGINT   NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    knowledge_base_id BIGINT  NOT NULL COMMENT '知识库ID',
    user_id          BIGINT   NOT NULL COMMENT '用户ID(IAM)',
    member_role      TINYINT  NOT NULL DEFAULT 4 COMMENT '角色 1拥有者 2管理员 3编辑者 4浏览者',
    created_by       BIGINT   NOT NULL COMMENT '创建者用户ID(IAM)',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted          TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_member_deleted (knowledge_base_id, user_id, deleted),
    KEY idx_knowledge_member_user_id (user_id),
    KEY idx_knowledge_member_base_id (knowledge_base_id),
    KEY idx_knowledge_member_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库成员表';

CREATE TABLE knowledge_folder (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '目录ID',
    knowledge_base_id BIGINT      NOT NULL COMMENT '知识库ID',
    parent_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '父目录ID 0表示根目录',
    name             VARCHAR(128) NOT NULL COMMENT '目录名称',
    sort_num         INT          NOT NULL DEFAULT 0 COMMENT '同级排序值',
    created_by       BIGINT       NOT NULL COMMENT '创建者用户ID(IAM)',
    updated_by       BIGINT                NULL COMMENT '更新者用户ID(IAM)',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_folder_name_deleted (knowledge_base_id, parent_id, name, deleted),
    KEY idx_knowledge_folder_base_id (knowledge_base_id),
    KEY idx_knowledge_folder_parent_id (parent_id),
    KEY idx_knowledge_folder_deleted (deleted),
    KEY idx_knowledge_folder_created_by (created_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库目录表';

CREATE TABLE knowledge_document (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    knowledge_base_id BIGINT        NOT NULL COMMENT '知识库ID',
    folder_id         BIGINT        NOT NULL DEFAULT 0 COMMENT '所属目录ID 0表示未分类',
    title             VARCHAR(256)  NOT NULL COMMENT '文档标题',
    original_filename VARCHAR(512)  NOT NULL DEFAULT '' COMMENT '原始文件名',
    object_key        VARCHAR(512)  NOT NULL DEFAULT '' COMMENT 'MinIO对象Key',
    content_type      VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '文件MIME类型',
    file_size         BIGINT        NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    checksum          VARCHAR(128)           NULL COMMENT '文件内容哈希(SHA-256)',
    ingest_status     TINYINT       NOT NULL DEFAULT 1 COMMENT '入库状态 1待处理 2处理中 3成功 4失败',
    version           INT           NOT NULL DEFAULT 1 COMMENT '当前版本号',
    status            TINYINT       NOT NULL DEFAULT 1 COMMENT '文档状态 1草稿 2已发布 3已归档',
    visibility        TINYINT       NOT NULL DEFAULT 1 COMMENT '可见范围 1私有 2部门 3公开',
    created_by        BIGINT        NOT NULL COMMENT '创建者用户ID(IAM)',
    updated_by        BIGINT                 NULL COMMENT '更新者用户ID(IAM)',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    KEY idx_knowledge_document_base_id (knowledge_base_id),
    KEY idx_knowledge_document_folder_id (folder_id),
    KEY idx_knowledge_document_status (status),
    KEY idx_knowledge_document_ingest_status (ingest_status),
    KEY idx_knowledge_document_deleted (deleted),
    KEY idx_knowledge_document_created_by (created_by),
    KEY idx_knowledge_document_object_key (object_key(128))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档主表';

CREATE TABLE knowledge_document_version (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '版本ID',
    document_id       BIGINT        NOT NULL COMMENT '文档ID',
    version           INT           NOT NULL COMMENT '版本号',
    original_filename VARCHAR(512)  NOT NULL DEFAULT '' COMMENT '原始文件名',
    object_key        VARCHAR(512)  NOT NULL DEFAULT '' COMMENT 'MinIO对象Key',
    content_type      VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '文件MIME类型',
    file_size         BIGINT        NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    checksum          VARCHAR(128)           NULL COMMENT '文件内容哈希(SHA-256)',
    ingest_status     TINYINT       NOT NULL DEFAULT 1 COMMENT '入库状态 1待处理 2处理中 3成功 4失败',
    version_remark    VARCHAR(512)           NULL COMMENT '版本说明',
    created_by        BIGINT        NOT NULL COMMENT '创建者用户ID(IAM)',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_doc_version_deleted (document_id, version, deleted),
    KEY idx_knowledge_doc_version_document_id (document_id),
    KEY idx_knowledge_doc_version_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档版本表';

CREATE TABLE knowledge_document_acl (
    id               BIGINT   NOT NULL AUTO_INCREMENT COMMENT 'ACL记录ID',
    document_id      BIGINT   NOT NULL COMMENT '文档ID',
    knowledge_base_id BIGINT  NOT NULL COMMENT '知识库ID(冗余,便于查询)',
    subject_type     TINYINT  NOT NULL COMMENT '主体类型 1用户 2部门',
    subject_id       BIGINT   NOT NULL COMMENT '主体ID',
    permission_type  TINYINT  NOT NULL DEFAULT 1 COMMENT '权限类型 1查看 2编辑 3管理',
    created_by       BIGINT   NOT NULL COMMENT '创建者用户ID(IAM)',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted          TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0存在 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_doc_acl_deleted (document_id, subject_type, subject_id, permission_type, deleted),
    KEY idx_knowledge_doc_acl_document_id (document_id),
    KEY idx_knowledge_doc_acl_subject (subject_type, subject_id),
    KEY idx_knowledge_doc_acl_base_id (knowledge_base_id),
    KEY idx_knowledge_doc_acl_deleted (deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档访问控制表';
