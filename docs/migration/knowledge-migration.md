# Knowledge 业务迁移文档

本文档说明从旧项目 `DocBase-Back-End`（AgileBoot 单体）到 `knowledge-service` 的知识库
业务迁移细节。

## 1. 旧项目知识库业务分析

旧项目基于 AgileBoot 框架，知识库业务采用以下结构：

- **知识库分类**：`KnowledgeCategoryEntity` → `KnowledgeCategoryMapper`
- **知识库文档**：`KnowledgeDocumentEntity` → `KnowledgeDocumentMapper`
- **文档版本**：`KnowledgeDocumentVersion` → 存储文件版本
- **文档 ACL**：`KnowledgeDocumentAcl` → 细粒度文档权限
- **入库任务**：`KnowledgeIngestTask` → 文件导入和解析

权限使用 DataScopeEnum 五级数据权限模型（ALL/CUSTOM_DEFINE/SINGLE_DEPT/DEPT_TREE/ONLY_SELF）。

## 2. 新旧模型映射

### 表映射

| 旧表 | 新表 | 变化 |
| --- | --- | --- |
| `knowledge_category` | `knowledge_folder` | 重命名，移除 dept_id、ancestors |
| `knowledge_document` | `knowledge_document` | 移除 dept_id、doc_code、current_version_id |
| `knowledge_document_version` | `knowledge_document_version` | 基本一致 |
| `knowledge_document_acl` | `knowledge_document_acl` | 添加 knowledge_base_id 冗余字段 |
| — | `knowledge_base` | **新增**，知识库主表 |
| — | `knowledge_member` | **新增**，知识库成员表 |
| `event_outbox` | `event_outbox` | V1 已创建 |

### 关键设计变更

1. **新增 knowledge_base**：旧项目没有显式的"知识库"实体，文档直接关联部门。
   新模型引入知识库作为顶层容器，支持多知识库隔离。

2. **新增 knowledge_member**：旧项目使用部门数据权限控制访问。
   新模型使用显式的成员关系表，支持更灵活的权限管理。

3. **简化权限模型**：旧项目使用 DataScopeEnum 五级数据权限。
   新模型使用四级角色（OWNER/ADMIN/EDITOR/VIEWER），更直观。

4. **移除部门依赖**：旧项目文档和分类都关联 dept_id。
   新模型移除部门依赖，用户身份只保存 IAM 的 userId。

## 3. 数据库表结构

### knowledge_base（知识库主表）

```sql
CREATE TABLE knowledge_base (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512) NOT NULL DEFAULT '',
    owner_id        BIGINT NOT NULL COMMENT '所有者用户ID(IAM)',
    visibility      TINYINT NOT NULL DEFAULT 1 COMMENT '1私有 2部门 3公开',
    status          TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    sort_num        INT NOT NULL DEFAULT 0,
    created_by      BIGINT NOT NULL,
    updated_by      BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT NOT NULL DEFAULT 0
);
```

### knowledge_member（知识库成员表）

```sql
CREATE TABLE knowledge_member (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    member_role      TINYINT NOT NULL DEFAULT 4 COMMENT '1拥有者 2管理员 3编辑者 4浏览者',
    created_by       BIGINT NOT NULL,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT NOT NULL DEFAULT 0
);
```

### knowledge_folder（目录表）

```sql
CREATE TABLE knowledge_folder (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    parent_id        BIGINT NOT NULL DEFAULT 0,
    name             VARCHAR(128) NOT NULL,
    sort_num         INT NOT NULL DEFAULT 0,
    created_by       BIGINT NOT NULL,
    updated_by       BIGINT,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT NOT NULL DEFAULT 0
);
```

### knowledge_document（文档主表）

```sql
CREATE TABLE knowledge_document (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    folder_id         BIGINT NOT NULL DEFAULT 0,
    title             VARCHAR(256) NOT NULL,
    original_filename VARCHAR(512) NOT NULL DEFAULT '',
    object_key        VARCHAR(512) NOT NULL DEFAULT '',
    content_type      VARCHAR(128) NOT NULL DEFAULT '',
    file_size         BIGINT NOT NULL DEFAULT 0,
    checksum          VARCHAR(128),
    ingest_status     TINYINT NOT NULL DEFAULT 1 COMMENT '1待处理 2处理中 3成功 4失败',
    version           INT NOT NULL DEFAULT 1,
    status            TINYINT NOT NULL DEFAULT 1 COMMENT '1草稿 2已发布 3已归档',
    visibility        TINYINT NOT NULL DEFAULT 1,
    created_by        BIGINT NOT NULL,
    updated_by        BIGINT,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT NOT NULL DEFAULT 0
);
```

## 4. 权限设计

### 两层权限模型

**第一层：IAM 功能权限**
- `knowledge:base:list/create/update/delete`
- `knowledge:member:list/manage`
- `knowledge:folder:list/create/update/delete`
- `knowledge:document:list/create/update/delete`

**第二层：知识库资源权限**
- OWNER（1）：完全控制
- ADMIN（2）：管理成员、目录、文档
- EDITOR（3）：创建/编辑目录和文档
- VIEWER（4）：只读

### 超级管理员

拥有 `admin:all` 权限的用户可以绕过功能权限和资源成员限制，但仍保留审计字段。

## 5. Outbox 事件

### 事件类型

| 事件 | 说明 |
| --- | --- |
| `knowledge.base.created` | 知识库创建 |
| `knowledge.base.deleted` | 知识库删除 |
| `knowledge.document.registered` | 文档登记 |
| `knowledge.document.deleted` | 文档删除 |
| `knowledge.document.reingest-requested` | 请求重新入库 |

### 事件负载

```json
{
  "eventId": "uuid",
  "eventType": "knowledge.base.created",
  "aggregateType": "knowledge_base",
  "aggregateId": "123",
  "knowledgeBaseId": 123,
  "documentId": null,
  "objectKey": null,
  "operatorId": 1,
  "schemaVersion": 1,
  "occurredAt": "2026-08-04T10:00:00Z"
}
```

## 6. 未迁移内容

以下内容将在后续阶段迁移：

- **文件内容解析**：属于 ingest-service
- **PDF/Word/Excel 文本抽取**：属于 rag-service
- **RabbitMQ 消费处理**：属于 ingest-service
- **Embedding 和向量存储**：属于 rag-service
- **Chroma 写入和查询**：属于 rag-service
- **完整 RAG 链路**：属于 rag-service
- **对话和 SSE**：属于 chat-service
- **文档审核流程**：本阶段简化为状态字段
- **文档版本管理**：本阶段只记录版本号

## 7. 与 ingest-service 的交接边界

knowledge-service 通过 Outbox 事件通知 ingest-service：

- `knowledge.document.registered`：文档已登记，需要入库解析
- `knowledge.document.deleted`：文档已删除，需要清理向量
- `knowledge.document.reingest-requested`：请求重新入库

ingest-service 消费这些事件后，更新 `knowledge_document.ingest_status` 字段。
