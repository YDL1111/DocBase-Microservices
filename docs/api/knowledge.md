# Knowledge Service API 文档

Knowledge 服务提供知识库管理、目录管理、文档元数据管理和成员管理能力。所有接口通过
Gateway 路由到 `knowledge-service`。

## 路由前缀

| 前缀 | 说明 |
| --- | --- |
| `/api/knowledge/**` | 知识库、目录、文档、成员接口 |

## Secure multipart document upload

`POST /api/knowledge/bases/{knowledgeBaseId}/documents/upload` accepts `multipart/form-data` and returns
`ApiResponse<Long>` (`data` is the document ID). It requires `knowledge:document:create` or `admin:all`, and
regular users must be EDITOR or above in the active knowledge base.

| Field | Required | Notes |
| --- | --- | --- |
| `file` | yes | PDF, DOCX, XLSX, PPTX, or TXT; default limit 100 MiB |
| `title` | no | Defaults to the filename without its extension |
| `folderId` | no | Defaults to `0`; it must belong to this knowledge base |
| `visibility` | no | One of `1`, `2`, `3`; defaults to `1` |
| `clientRequestId` | yes | A 1-128 character idempotency key |

The service validates the extension, declared Content-Type, non-empty size, and safe filename. It generates the
object key itself in `knowledge/{knowledgeBaseId}/{yyyy}/{MM}/{uuid}/{safeFilename}`; clients must not send or
control an object key. Reusing the same client request ID with the same validated metadata returns the original
document ID. A different payload returns `IDEMPOTENCY_CONFLICT`.

An in-progress request holds a server-generated lease token. Before the configurable
`KNOWLEDGE_DOCUMENT_UPLOAD_LEASE_DURATION` (default `PT10M`, minimum `PT30S`) expires, a duplicate returns
`UPLOAD_IN_PROGRESS`. After a crash and lease expiry, exactly one retry atomically takes over
with a new token and object key; a stale attempt cannot complete, release, or delete the new attempt. Lease
timestamps use UTC. If object storage reports an upload failure after persisting an object, the service attempts
to delete only that attempt's object before releasing its lease; cleanup failures do not replace the upload error.

The older JSON `POST /api/knowledge/bases/{knowledgeBaseId}/documents` endpoint is now **internal-only**. It
requires an internal-registration JWT authority (or `admin:all`) and `X-Knowledge-Internal-Key` matching the
private `KNOWLEDGE_INTERNAL_REGISTRATION_API_KEY` configuration. Browser clients must use multipart upload.

### Browser upload behavior

The web client sends one file at a time through Gateway with a per-request 120 second timeout. It uses a UUID
`clientRequestId` for each new selection, reuses that UUID only when a network/timeout/5xx result is uncertain,
and creates a new UUID after file or metadata changes. Browser progress reports only transfer to Gateway/Knowledge;
the document list separately polls `ingestStatus` values `1` (pending) and `2` (processing) every four seconds.

## 认证方式

请求受保护接口时在 Header 中携带：

```
Authorization: Bearer <accessToken>
```

---

## 知识库接口

### GET /api/knowledge/bases

需认证：`knowledge:base:list` 或 `admin:all`

查询当前用户参与的知识库列表（分页）。

### GET /api/knowledge/bases/{id}

需认证：`knowledge:base:list` 或 `admin:all`

查询指定知识库详情。必须是该知识库的成员。

### POST /api/knowledge/bases

需认证：`knowledge:base:create` 或 `admin:all`

创建知识库。创建者自动成为 OWNER。

请求：
```json
{
  "name": "我的知识库",
  "description": "描述",
  "visibility": 1
}
```

### PUT /api/knowledge/bases/{id}

需认证：`knowledge:base:update` 或 `admin:all`

更新知识库。需要 ADMIN 或更高权限。

### DELETE /api/knowledge/bases/{id}

需认证：`knowledge:base:delete` 或 `admin:all`

删除知识库（软删除）。仅 OWNER 可操作。

---

## 成员接口

### GET /api/knowledge/bases/{id}/members

需认证：`knowledge:member:list` 或 `admin:all`

查询知识库成员列表。

### POST /api/knowledge/bases/{id}/members

需认证：`knowledge:member:manage` 或 `admin:all`

添加成员。

请求：
```json
{
  "userId": 123,
  "role": 3
}
```

角色：1=OWNER, 2=ADMIN, 3=EDITOR, 4=VIEWER

### PUT /api/knowledge/bases/{id}/members/{userId}

需认证：`knowledge:member:manage` 或 `admin:all`

修改成员角色。

### DELETE /api/knowledge/bases/{id}/members/{userId}

需认证：`knowledge:member:manage` 或 `admin:all`

移除成员。不能移除 OWNER。

---

## 目录接口

### GET /api/knowledge/bases/{id}/folders/tree

需认证：`knowledge:folder:list` 或 `admin:all`

获取知识库目录树。

### POST /api/knowledge/bases/{id}/folders

需认证：`knowledge:folder:create` 或 `admin:all`

创建目录。需要 EDITOR 或更高权限。

### PUT /api/knowledge/bases/{id}/folders/{folderId}

需认证：`knowledge:folder:update` 或 `admin:all`

更新目录。需要 EDITOR 或更高权限。

### DELETE /api/knowledge/bases/{id}/folders/{folderId}

需认证：`knowledge:folder:delete` 或 `admin:all`

删除目录。需要 EDITOR 或更高权限。不能有子目录。

---

## 文档接口

### GET /api/knowledge/bases/{id}/documents

需认证：`knowledge:document:list` 或 `admin:all`

查询知识库文档列表（分页）。

### GET /api/knowledge/documents/{documentId}

需认证：`knowledge:document:list` 或 `admin:all`

查询文档详情。

### POST /api/knowledge/bases/{id}/documents (internal compatibility only)

需认证：`knowledge:document:create` 或 `admin:all`

登记文档元数据（不解析文件内容）。需要 EDITOR 或更高权限。

请求：
```json
{
  "folderId": 0,
  "title": "文档标题",
  "originalFilename": "example.pdf",
  "objectKey": "documents/.../file.pdf",
  "contentType": "application/pdf",
  "fileSize": 1024000,
  "checksum": "sha256-hash",
  "visibility": 1
}
```

### PUT /api/knowledge/documents/{documentId}

需认证：`knowledge:document:update` 或 `admin:all`

更新文档元数据。

### DELETE /api/knowledge/documents/{documentId}

需认证：`knowledge:document:delete` 或 `admin:all`

删除文档（软删除）。

---

## 权限矩阵

| 操作 | 所需角色 |
| --- | --- |
| 查看知识库/文档 | VIEWER+ |
| 创建/编辑目录、文档 | EDITOR+ |
| 管理成员 | ADMIN+ |
| 删除知识库 | OWNER |
| 所有操作 | admin:all（超级管理员） |

---

## 常见错误码

| HTTP | Code | 说明 |
| ---: | --- | --- |
| 401 | `UNAUTHORIZED` | 未认证或 Token 无效 |
| 403 | `FORBIDDEN` | 无权限访问 |
| 400 | `PERMISSION_DENIED` | 权限不足 |
| 400 | `NOT_A_MEMBER` | 不是知识库成员 |
| 400 | `KNOWLEDGE_BASE_NOT_FOUND` | 知识库不存在 |
| 400 | `FOLDER_NOT_FOUND` | 目录不存在 |
| 400 | `DOCUMENT_NOT_FOUND` | 文档不存在 |
| 400 | `MEMBER_NOT_FOUND` | 成员不存在 |
| 400 | `MEMBER_ALREADY_EXISTS` | 成员已存在 |
| 400 | `FOLDER_HAS_CHILDREN` | 目录有子目录 |
| 400 | `CIRCULAR_REFERENCE` | 目录循环引用 |
| 400 | `PARENT_IN_DIFFERENT_BASE` | 父目录属于不同知识库 |
| 400 | `CANNOT_REMOVE_OWNER` | 不能移除所有者 |
| 400 | `CANNOT_CHANGE_OWNER` | 不能修改所有者角色 |
