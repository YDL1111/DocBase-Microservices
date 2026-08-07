# Chat Service 第一阶段迁移

## 目标

完成 chat-service 第一阶段：AI 会话管理、消息持久化、会话所有权校验、Knowledge 可见文档 ID 查询、Chat 使用 WebClient 调用 RAG SSE、Gateway 到 Chat 的 SSE 透传、用户/助手消息落库、SSE 错误/取消/完成处理、并发流限制。

## 旧项目 → 新模块映射

| 旧项目 | 新模块 |
|--------|--------|
| `AiChatController` (`/ai/chat`) | `ChatSessionController` + `ChatStreamController` (`/api/ai/chat`) |
| `AiChatApplicationService` | `ChatStreamOrchestrator` + `ChatSessionService` |
| `PythonAiClient` (RestClient/HttpURLConnection) | `RagChatStreamService` (WebClient) |
| `AiChatSessionEntity` | `ChatSession` |
| `AiChatMessageEntity` | `ChatMessage` |
| `visible_doc_ids` 计算（旧 Java 内） | `KnowledgeServiceClient` → knowledge-service `visible-document-ids` |
| `SseEmitter` / `StreamingResponseBody` | `ResponseBodyEmitter` + WebClient Flux |
| 无幂等 | `client_request_id` 唯一约束 |
| 无并发限制 | Redis `docbase:chat:stream:{userId}` 锁 |

## 数据库设计（V2 Flyway）

```sql
ai_chat_session (id, user_id, knowledge_base_id, title, status, created_at, updated_at, deleted)
ai_chat_message (id, session_id, user_id, role, content, status, client_request_id,
                 sources_json, error_code, created_at, completed_at, deleted)
```

索引：`idx_chat_session_user_updated`、`idx_chat_message_session_created`、`uk_chat_message_client_request_id(client_request_id, deleted)`。

## visible_document_ids 权限链路

1. 客户端 → Gateway → chat-service（携带 JWT）
2. chat-service 调用 knowledge-service `GET /bases/{kbId}/visible-document-ids`（透传 Authorization + Trace-ID）
3. knowledge-service 根据 JWT Principal 计算：
   - 知识库存在且启用
   - 普通用户至少是成员
   - 仅返回 deleted=0、status=已发布、ingest_status=入库成功 且用户可见的文档
   - PUBLIC：所有成员可见
   - PRIVATE：仅创建者或 ACL 授权用户
   - DEPT：fail-closed（JWT 无可靠部门信息）
   - admin:all 绕过资源权限但不绕过存在性
4. chat-service 将结果传给 RAG，RAG 在 Chroma 检索阶段过滤
5. 空列表 = 无权限（不解释为全库检索）

## 会话所有权与 IDOR

- 每次读取/修改/删除都使用 `sessionId + currentUserId` 联合校验
- 非所有者返回 `AccessDeniedException`（403），与不存在同形
- admin:all 不能查看他人私人会话

## Redis 并发锁

- Key：`docbase:chat:stream:{userId}`
- TTL：120s（可配置）
- 单用户并发流上限：初始 1（可配置）
- 使用随机 token 标识持有者，释放时用 compare-and-delete Lua 脚本
- 所有完成/失败/超时/取消路径都释放锁

## client_request_id 幂等

- 客户端重试时使用相同 clientRequestId
- `ai_chat_message.client_request_id` 唯一约束
- 重复请求不重复写 USER 消息或发起第二条 RAG 请求

## 测试

- 27 个测试全部通过（ChatSessionServiceTest 12、ChatSecurityIntegrationTest 8、RagChatStreamServiceTest 6、contextLoads 1）
- 覆盖：401/403、IDOR、会话所有权、admin:all 不绕过所有权、消息幂等、SSE token 转发、source 过滤、错误映射、API Key 透传、空 visible_doc_ids

## 未迁移

- Vue 页面
- 完整 Agent 或工具调用
- 管理员 Agent
- WebSocket
- 同步 query（RAG 仅支持 SSE）
