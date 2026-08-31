# 导入任务 API

Ingest 服务提供导入任务查询、状态跟踪、手动重试和取消能力。所有浏览器请求通过 Gateway 的 `/api/ingest/**` 路径访问。

## 路由前缀

| 前缀 | 说明 |
| --- | --- |
| `/api/ingest/**` | 导入任务管理接口 |

## 认证方式

请求受保护接口时在 Header 中携带：

```
Authorization: Bearer <accessToken>
```

---

## 任务管理接口

### GET /api/ingest/tasks

需认证：`ingest:task:list` 或 `admin:all`

查询导入任务列表（分页）。

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| current | long | 否 | 页码，默认 1 |
| size | long | 否 | 每页大小，默认 20 |
| status | string | 否 | 状态过滤 |

### GET /api/ingest/tasks/{taskId}

需认证：`ingest:task:view` 或 `admin:all`

查询单个任务详情。

### POST /api/ingest/tasks/{taskId}/retry

需认证：`ingest:task:retry` 或 `admin:all`

手动重试失败的任务。仅 FAILED 或 DEAD 状态可重试。

### POST /api/ingest/tasks/{taskId}/cancel

需认证：`ingest:task:cancel` 或 `admin:all`

取消待处理的任务。仅 PENDING 或 RETRY_WAIT 状态可取消。

---

## 任务状态说明

| 状态 | 说明 |
| --- | --- |
| PENDING | 待处理 |
| PROCESSING | 处理中 |
| DISPATCHED | 已分发到 RAG |
| SUCCEEDED | 成功完成 |
| FAILED | 失败 |
| RETRY_WAIT | 等待重试 |
| DEAD | 超过最大重试次数 |
| CANCELLED | 已取消 |

---

## 常见错误码

| HTTP | Code | 说明 |
| ---: | --- | --- |
| 401 | `UNAUTHORIZED` | 未认证或 Token 无效 |
| 403 | `FORBIDDEN` | 无权限访问 |
| 400 | `TASK_NOT_FOUND` | 任务不存在 |
| 400 | `INVALID_STATUS` | 状态不允许该操作 |
| 400 | `INVALID_STATUS_TRANSITION` | 非法状态转换 |
