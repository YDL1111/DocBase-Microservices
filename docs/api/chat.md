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

## Browser stream client

The browser client uses `fetch` with `POST /api/ai/chat/stream`, JSON request data, and an `Authorization` header. It does not use `EventSource` (which cannot send this POST body) or Axios for the streaming response.

- A `401` received before any SSE data triggers the shared token refresh coordinator, then retries the exact request once. Network failures, `5xx`, `403`, and a second `401` are not retried automatically.
- `clientRequestId` is required by the client API. Generation and recovery policy belong to Phase 4C.
- EOF without a `done` or `error` event is an incomplete stream. A user cancellation is propagated through `AbortSignal` to fetch.
- After a `done` or `error` event the client keeps reading until the server closes the stream, while ignoring any later events, so the server can persist its terminal state normally.

## Chat page streaming behavior (Phase 4C2A)

### Failure recovery & history reconciliation (Phase 4C2A)

- Transport-level failures whose outcome is uncertain (`NETWORK_ERROR`, `STREAM_INCOMPLETE`, premature disconnect, and transport-shape `HTTP_ERROR`) never display as success. The temporary USER/ASSISTANT messages are kept, the transport is allowed to fully settle (drain), and the UI shows a safe "result pending" state while it requests the persisted history for the active session.
- If the history already contains a USER message with the same `clientRequestId`, the persisted record replaces the temporary one and no new RAG call is made. If the history does not contain the request yet, the temporary content is preserved and a retry entry is exposed; retrying reuses the original `clientRequestId`.
- Reconciliation is guarded by the stream generation, the selected session id, and the mounted flag. A late reconciliation result never writes into a different session, never restores a deleted session, and never overwrites a newer stream started after it.

### clientRequestId lifecycle (Phase 4C2A)

- A brand-new user intent generates a fresh UUID via `crypto.randomUUID()`. The initial-401 retry inside the stream client naturally reuses the same id.
- Editing the question and resending generates a new id.
- Retrying an uncertain result reuses the original id; the user can never supply an id, and none is derived from timestamps or indices.
- A `DUPLICATE_REQUEST` SSE error is treated as "the server already knows this id": the client reconciles with history instead of synthesizing a new id to bypass the idempotency guard. If the persisted record is still STREAMING, the UI shows a processing state and offers a manual recheck.

### Manual refresh & recheck

- The message history has an explicit refresh action. It is disabled while a stream is generating, draining, or cancelling, so a history response can never clobber a live temporary message. Concurrent refreshes coalesce into a single pending refresh, and a refresh captures the session id plus request sequence so a stale response is ignored.
- A manual recheck re-runs reconciliation for the current attempt. Repeated clicks coalesce into the single in-flight recheck (`pendingRecheck`), and the latest user request is never silently dropped.

### Recovery states

- `GENERATING` (receiving tokens), `DRAINING` (terminal received, transport ending), `CANCELLING` (stop in flight), `SYNCING` (reconciling with history), `UNCERTAIN` (pending confirmation), `RETRYABLE` (confirmed absent, retry offered), plus the terminal `COMPLETED` / `FAILED` / `CANCELLED`.
- During draining and cancelling the input keeps its content and stays editable, but the send button is disabled. Status text is generic and never exposes backend stack traces, URLs, SQL, MinIO keys, prompts, or raw error payloads.

## Chat page streaming behavior (Phase 4C1)

- The page sends only from a selected, valid session that has a positive `knowledgeBaseId`. The user cannot change the knowledge base from the question box; the selected session's stored binding is sent with the request.
- Each new user intent uses one browser-generated UUID `clientRequestId`. The same value stays with the exact request if the stream client's initial-401 retry runs.
- The USER message and one temporary ASSISTANT message are added immediately. Tokens append to that same assistant message, and `sources` attach to that same message even if they arrive before tokens.
- Every callback is guarded by a stream generation, its `AbortController`, the selected-session snapshot, and component mounted state. Switching sessions, deleting the active session, leaving the route, unmounting, replacing a live stream, or explicit stop invalidates the previous generation before aborting it.
- On `done`, the UI retains the temporary result while it reloads the selected session history; the persisted history becomes authoritative only if the same stream/session is still current. This history-only settling state is replaceable: a new accepted question invalidates the old refresh instead of being dropped. A failed refresh leaves the completed temporary result visible for manual refresh.
- An SSE `error`, incomplete EOF, or client failure is never displayed as successful. An error immediately ends the visible generating state while the client drains the terminal stream in the background; it cannot subsequently be changed to cancelled. Terminal error drain and user cancellation are non-replaceable settling states: the question box keeps its text, but sending stays unavailable until the prior `consume()` Promise settles, so the server can persist the terminal state and release its concurrency lock. UI messages use safe generic text and never expose backend stack traces, URLs, SQL, MinIO keys, or prompts. Explicit user stop marks only an actively generating temporary response cancelled; background cancellation from navigation/session changes does not show a toast in the new session.
- Sources display only validated `file_name` and optional positive `page` values. `document_id` must be a positive safe integer; no object keys, download URLs, or direct object-storage links are rendered.

## 数据库

- `ai_chat_session`：会话主表
- `ai_chat_message`：消息表（client_request_id 唯一约束防重）

详见 [数据库设计](../migration/chat-migration.md)。
