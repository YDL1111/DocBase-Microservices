# Chat Service API

AI 会话管理 + RAG 流式问答。所有路由经 Gateway `/api/ai/**` 到达 chat-service。

## 权限

| 端点 | 权限 |
|------|------|
| GET /api/ai/chat/sessions | `ai:chat:list` 或 `admin:all` |
| POST /api/ai/chat/sessions | `ai:chat:list` 或 `admin:all` |
| GET /api/ai/chat/sessions/{sessionId}/messages | `ai:chat:list` 或 `admin:all` |
| DELETE /api/ai/chat/sessions/{sessionId} | `ai:chat:list` 或 `admin:all` |
| POST /api/ai/chat/stream | `ai:chat:query` 或 `admin:all` |
| POST /api/ai/chat/query | 暂不支持（返回 NOT_IMPLEMENTED） |

admin:all 满足菜单权限，但**不能**读取/删除其他用户的私人会话。

## 安全边界

- userId 仅来自已验证的 JWT Principal，不接受客户端传入
- visibleDocumentIds 完全由 knowledge-service 根据 JWT 身份计算
- 每次读写会话/消息都使用 `sessionId + currentUserId` 联合校验
- 已有会话使用其存储的 knowledgeBaseId，不信任请求中的 KB ID
- RAG API Key 仅通过环境变量注入，日志中不输出

## SSE 事件协议

每个 SSE 事件都是统一 JSON 格式：`data: {"type":"<type>","data":<payload>}`

```
data: {"type":"session","data":{"sessionId":1,"messageId":2}}
data: {"type":"token","data":"你"}
data: {"type":"token","data":"好"}
data: {"type":"sources","data":[{"document_id":1,"file_name":"x.pdf","page":3}]}
data: {"type":"done","data":null}
data: {"type":"error","data":{"code":"RAG_UNAVAILABLE","message":"AI 服务暂时不可用"}}
```

| type | payload 格式 | 说明 |
|------|-------------|------|
| session | `{"sessionId":N,"messageId":N}` | 流首条，返回会话和助手消息 ID |
| token | 字符串 | 逐 token 输出 |
| sources | `[{"document_id","file_name","page"}]` | 来源文档（已过滤可见范围） |
| done | null | 流正常结束 |
| error | `{"code","message"}` | 错误终止 |

错误码：`INVALID_INPUT`、`QUESTION_TOO_LONG`、`FORBIDDEN`、`KB_MISMATCH`、`CONCURRENT_STREAM_LIMIT`、`DUPLICATE_REQUEST`、`RAG_UNAVAILABLE`、`RAG_TIMEOUT`、`RAG_ERROR`、`RAG_INCOMPLETE`、`NOT_IMPLEMENTED`、`INTERNAL_ERROR`、`STREAM_ERROR`

## 数据库

- `ai_chat_session`：会话主表
- `ai_chat_message`：消息表（client_request_id 唯一约束防重）

详见 [数据库设计](../migration/chat-migration.md)。
