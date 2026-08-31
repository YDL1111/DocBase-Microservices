# 知识库 API

Knowledge 服务负责知识库、目录、成员、文档元数据、文件上传和可见范围计算。所有浏览器请求通过 Gateway 的 `/api/knowledge/**` 路径访问。

## 权限模型

接口同时校验 RBAC 权限和知识库成员角色：

| 操作 | 最低成员角色 |
| --- | --- |
| 查看知识库和文档 | VIEWER |
| 创建、编辑目录和文档 | EDITOR |
| 管理成员 | ADMIN |
| 删除知识库 | OWNER |

超级管理员的 `admin:all` 可以通过功能授权，但服务仍会执行资源存在性和业务约束校验。

可见范围值：`1` 为私有、`2` 为组织内、`3` 为公开。组织内内容要求当前用户拥有组织且与资源的组织快照一致。

## 知识库

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/knowledge/bases` | `knowledge:base:list` | 查询当前用户可见的知识库 |
| GET | `/api/knowledge/bases/{id}` | `knowledge:base:list` | 查询知识库详情 |
| POST | `/api/knowledge/bases` | `knowledge:base:create` | 创建知识库，创建者成为 OWNER |
| PUT | `/api/knowledge/bases/{id}` | `knowledge:base:update` | 更新名称、描述和可见范围 |
| DELETE | `/api/knowledge/bases/{id}` | `knowledge:base:delete` | 软删除知识库，仅 OWNER 可执行 |

创建示例：

```json
{
  "name": "产品知识库",
  "description": "产品说明与常见问题",
  "visibility": 2
}
```

## 成员

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/knowledge/bases/{id}/members` | `knowledge:member:list` | 查询成员 |
| POST | `/api/knowledge/bases/{id}/members` | `knowledge:member:manage` | 添加成员 |
| PUT | `/api/knowledge/bases/{id}/members/{userId}` | `knowledge:member:manage` | 修改成员角色 |
| DELETE | `/api/knowledge/bases/{id}/members/{userId}` | `knowledge:member:manage` | 移除成员 |

角色值：`1=OWNER`、`2=ADMIN`、`3=EDITOR`、`4=VIEWER`。Owner 不能通过普通成员接口被移除或降级。

## 目录

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/knowledge/bases/{id}/folders/tree` | `knowledge:folder:list` | 获取目录树 |
| POST | `/api/knowledge/bases/{id}/folders` | `knowledge:folder:create` | 创建目录 |
| PUT | `/api/knowledge/bases/{id}/folders/{folderId}` | `knowledge:folder:update` | 更新或移动目录 |
| DELETE | `/api/knowledge/bases/{id}/folders/{folderId}` | `knowledge:folder:delete` | 删除空目录 |

父目录必须属于同一个知识库，禁止循环引用；存在子目录时不能删除。

## 文档查询与操作

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/knowledge/bases/{id}/documents` | `knowledge:document:list` | 分页查询文档 |
| GET | `/api/knowledge/documents/{documentId}` | `knowledge:document:list` | 查询文档详情 |
| GET | `/api/knowledge/documents/{documentId}/content` | `knowledge:document:list` | 获取可预览的文档内容 |
| PUT | `/api/knowledge/documents/{documentId}` | `knowledge:document:update` | 编辑标题、目录和可见范围 |
| POST | `/api/knowledge/documents/{documentId}/reingest` | `knowledge:document:update` | 请求重新入库 |
| DELETE | `/api/knowledge/documents/{documentId}` | `knowledge:document:delete` | 软删除并触发向量清理 |

`GET /api/knowledge/bases/{knowledgeBaseId}/visible-document-ids` 用于 Chat 服务查询当前用户可见文档，返回结果有数量上限。该接口依赖已验证 JWT，不接受浏览器指定用户 ID。

## 文件上传

`POST /api/knowledge/bases/{knowledgeBaseId}/documents/upload` 使用 `multipart/form-data`，成功时 `data` 为文档 ID。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | PDF、DOCX、XLSX、PPTX 或 TXT，默认上限 100 MiB |
| `title` | 否 | 默认使用去除扩展名后的文件名 |
| `folderId` | 否 | 默认根目录 `0`，必须属于当前知识库 |
| `visibility` | 否 | `1`、`2` 或 `3`，默认 `1` |
| `clientRequestId` | 是 | 1～128 字符的幂等键 |

服务端校验扩展名、Content-Type、文件大小和安全文件名，并自行生成对象 Key。客户端不能指定 MinIO 路径。

相同 `clientRequestId` 与相同元数据重复上传时返回原文档 ID；请求内容不同则返回 `IDEMPOTENCY_CONFLICT`。上传中的请求由带租约的服务端令牌保护，实例崩溃且租约过期后只允许一个请求接管，旧请求不能完成或删除新请求的对象。

浏览器每次选择新文件时生成新的 UUID；仅在网络超时或服务端结果不确定时复用原 ID。上传进度只代表文件已传到 Knowledge，最终解析状态应从文档或导入任务中查询。

## 内部文档登记

`POST /api/knowledge/bases/{knowledgeBaseId}/documents` 仅用于受信任服务登记已存在对象的元数据。调用者需要内部权限，并提供匹配的 `X-Knowledge-Internal-Key`。浏览器必须使用文件上传接口。

## 常见错误码

| 错误码 | 含义 |
| --- | --- |
| `KNOWLEDGE_BASE_NOT_FOUND` | 知识库不存在或不可见 |
| `DOCUMENT_NOT_FOUND` | 文档不存在或不可见 |
| `FOLDER_NOT_FOUND` | 目录不存在 |
| `NOT_A_MEMBER` | 不是所需的知识库成员 |
| `PERMISSION_DENIED` | 成员角色不足 |
| `ORGANIZATION_REQUIRED` | 组织内可见资源缺少组织信息 |
| `IDEMPOTENCY_CONFLICT` | 幂等键已被不同请求使用 |
| `UPLOAD_IN_PROGRESS` | 同一上传请求仍在处理中 |
| `FOLDER_HAS_CHILDREN` | 目录存在子目录 |
| `CIRCULAR_REFERENCE` | 目录移动形成循环 |
| `CANNOT_REMOVE_OWNER` | 不能移除知识库 Owner |
