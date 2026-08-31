# API 接口索引

浏览器统一访问 Gateway 暴露的 `/api/**` 路径，不应直接请求业务服务，也不能自行拼接 `X-User-*` 身份头。

## 服务入口

| 文档 | 路径前缀 | 主要能力 |
| --- | --- | --- |
| [认证与系统管理](iam.md) | `/api/auth/**`、`/api/system/**` | 登录注册、用户、角色、菜单和组织 |
| [知识库](knowledge.md) | `/api/knowledge/**` | 知识库、目录、成员、文档和可见范围 |
| [导入任务](ingest.md) | `/api/ingest/**` | 任务查询、重试、取消和状态跟踪 |
| [AI 会话](chat.md) | `/api/ai/chat/**` | 会话、知识库绑定、消息和 SSE 问答 |

## 通用约定

受保护接口使用 Bearer Token：

```http
Authorization: Bearer <accessToken>
```

普通 JSON 接口返回统一结构：

```json
{
  "success": true,
  "code": "OK",
  "message": "",
  "data": {}
}
```

- `code` 是前端处理业务错误的稳定标识，不应依赖异常文本判断。
- ID 使用正整数；时间使用 ISO 8601 字符串。
- 分页接口通常使用 `current` 和 `size`，具体字段以服务文档为准。
- SSE 接口返回事件流，不使用普通 JSON 包装，详见 [AI 会话接口](chat.md)。
- 内部接口会同时校验服务身份和内部 API Key，浏览器不得调用。

## 认证错误

| HTTP 状态 | 错误码 | 含义 |
| ---: | --- | --- |
| 400 | `VALIDATION_ERROR` | 请求字段不符合约束 |
| 401 | `UNAUTHORIZED` | 未登录、Token 过期或签名无效 |
| 403 | `FORBIDDEN` | 已认证但缺少权限 |
| 429 | `TOO_MANY_REQUESTS` | 触发限流 |
| 500 | `INTERNAL_ERROR` | 未分类的服务端错误 |

各业务错误码请查看对应服务文档。
