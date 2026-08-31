# AI 会话 API

Chat 服务负责会话、消息持久化、知识库绑定、权限范围查询和 RAG 流式转发。所有接口通过 Gateway 的 `/api/ai/chat/**` 路径访问。

超级管理员可以通过功能权限，但不能读取或删除其他用户的私人会话。所有会话和消息操作都使用当前 JWT 用户与资源所有者联合校验。

## 会话与消息

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/ai/chat/sessions` | `ai:chat:list` | 分页查询自己的会话 |
| POST | `/api/ai/chat/sessions` | `ai:chat:list` | 创建会话 |
| PUT | `/api/ai/chat/sessions/{sessionId}/knowledge-bases` | `ai:chat:list` | 全量替换绑定知识库 |
| GET | `/api/ai/chat/sessions/{sessionId}/messages` | `ai:chat:list` | 查询会话历史 |
| DELETE | `/api/ai/chat/sessions/{sessionId}/messages/{messageId}` | `ai:chat:list` | 删除已结束的助手回复 |
| DELETE | `/api/ai/chat/sessions/{sessionId}` | `ai:chat:list` | 删除自己的会话 |

创建会话示例：

```json
{
  "title": "产品资料问答",
  "knowledgeBaseIds": [1, 2]
}
```

`knowledgeBaseIds` 最多 20 个。空数组表示普通对话，不使用企业知识库；会话创建后仍可通过绑定接口改为零个、一个或多个知识库。`knowledgeBaseId` 单值字段仅用于兼容旧客户端，新代码应使用数组字段。

绑定接口采用全量替换语义：

```json
{
  "knowledgeBaseIds": [2, 5]
}
```

## 流式问答

`POST /api/ai/chat/stream` 需要 `ai:chat:query` 权限，Content-Type 为 `application/json`，响应为 `text/event-stream`。

```json
{
  "sessionId": 12,
  "knowledgeBaseIds": [2, 5],
  "question": "请总结产品的部署要求",
  "clientRequestId": "15c46f38-3374-4f91-9ea8-6d8a61c957f8"
}
```

- `question` 长度为 1～4000 字符。
- `clientRequestId` 由浏览器生成，用于识别重试产生的重复请求。
- 已存在会话时，以会话中持久化的知识库绑定为准，不信任请求伪造的范围。
- 可见文档 ID 由 Knowledge 根据当前 JWT 计算，客户端不能直接提交。
- `POST /api/ai/chat/query` 是兼容入口，当前返回 `NOT_IMPLEMENTED`；同步问答不会伪装成流式结果。

## 流式事件

每条事件的 `data` 都是统一 JSON：

```text
data: {"type":"session","data":{"sessionId":12,"messageId":36}}
data: {"type":"token","data":"部署"}
data: {"type":"sources","data":[{"document_id":8,"file_name":"部署手册.pdf","page":3}]}
data: {"type":"done","data":null}
```

| 类型 | 数据 | 含义 |
| --- | --- | --- |
| `session` | 会话 ID、消息 ID | 流建立后的资源标识 |
| `token` | 字符串 | 增量回答内容 |
| `sources` | 来源数组 | 已通过可见范围过滤的引用 |
| `done` | `null` | 正常完成 |
| `error` | 错误码和安全提示 | 业务或传输错误 |

浏览器使用 `fetch` 读取 POST SSE，不使用无法携带请求体的 `EventSource`。收到 `done` 或 `error` 后继续读取到服务端关闭连接，但忽略多余事件，确保服务端可以完成消息状态持久化。

## 幂等与中断恢复

- 一个新的用户意图生成新的 `clientRequestId`。
- 首次 401 触发令牌刷新后，原请求只自动重试一次并复用同一个 ID。
- 网络中断或流提前结束时，前端不直接显示成功，而是查询会话历史核对结果。
- 如果历史已存在相同 ID 的用户消息，则使用持久化消息替换临时消息，不再次调用 RAG。
- 结果仍不确定时保留用户输入并提供重新核对或重试；重试复用原 ID。
- 切换会话、删除会话、停止生成或卸载页面会取消旧流，迟到回调不能写入新会话。

## 删除约束

单条消息删除只允许操作当前用户会话中已经结束的助手回复。用户问题、生成中的消息、其他用户会话或不匹配的 `sessionId + messageId` 会被拒绝。

## 常见错误码

| 错误码 | 含义 |
| --- | --- |
| `INVALID_INPUT` | 请求字段无效 |
| `QUESTION_TOO_LONG` | 问题超过长度限制 |
| `KB_MISMATCH` | 请求知识库与会话绑定不一致 |
| `DUPLICATE_REQUEST` | 相同幂等 ID 已存在 |
| `CONCURRENT_STREAM_LIMIT` | 当前用户并发生成数超过限制 |
| `RAG_UNAVAILABLE` | RAG 服务不可用 |
| `RAG_TIMEOUT` | RAG 调用超时 |
| `RAG_INCOMPLETE` | 流未正常结束 |
| `STREAM_ERROR` | SSE 传输异常 |
