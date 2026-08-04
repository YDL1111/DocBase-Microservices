# Knowledge Service 事件契约

## 概述

Knowledge 服务通过 Outbox 模式发布领域事件。事件在业务事务中写入 `event_outbox` 表，
由后台发布器异步发送到 RabbitMQ。

## 事件契约定义

事件契约定义在 `com.docbase.contracts.KnowledgeEvent` 中。

```java
public record KnowledgeEvent(
    UUID eventId,           // 唯一事件标识
    String eventType,       // 事件类型
    String aggregateType,   // 聚合类型
    String aggregateId,     // 聚合ID
    Long knowledgeBaseId,   // 知识库ID
    Long documentId,        // 文档ID（可选）
    String objectKey,       // MinIO对象Key（可选）
    Long operatorId,        // 操作者用户ID
    int schemaVersion,      // 事件schema版本
    Instant occurredAt      // 发生时间
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

## 事件类型

### knowledge.base.created

知识库创建时发布。

| 字段 | 值 |
| --- | --- |
| aggregateType | `knowledge_base` |
| aggregateId | 知识库ID |
| knowledgeBaseId | 知识库ID |
| documentId | null |
| objectKey | null |

### knowledge.base.deleted

知识库删除时发布。

| 字段 | 值 |
| --- | --- |
| aggregateType | `knowledge_base` |
| aggregateId | 知识库ID |
| knowledgeBaseId | 知识库ID |

### knowledge.document.registered

文档元数据登记时发布。

| 字段 | 值 |
| --- | --- |
| aggregateType | `document` |
| aggregateId | 文档ID |
| knowledgeBaseId | 所属知识库ID |
| documentId | 文档ID |
| objectKey | MinIO对象Key |

### knowledge.document.deleted

文档删除时发布。

| 字段 | 值 |
| --- | --- |
| aggregateType | `document` |
| aggregateId | 文档ID |
| knowledgeBaseId | 所属知识库ID |
| documentId | 文档ID |
| objectKey | MinIO对象Key |

### knowledge.document.reingest-requested

请求重新入库时发布。

| 字段 | 值 |
| --- | --- |
| aggregateType | `document` |
| aggregateId | 文档ID |
| knowledgeBaseId | 所属知识库ID |
| documentId | 文档ID |
| objectKey | MinIO对象Key |

## Outbox 状态流转

```
PENDING -> PUBLISHED
        -> FAILED (重试)
        -> DLQ (最终失败)
```

### 状态说明

- **PENDING**：事件已写入，等待发布
- **PUBLISHED**：事件已成功发送到 RabbitMQ
- **FAILED**：发布失败，等待重试
- **DLQ**：超过重试次数，进入死信队列

## 消费者注意事项

1. **幂等消费**：使用 `eventId` 做去重
2. **顺序保证**：同一聚合的事件按 `occurredAt` 顺序处理
3. **错误处理**：消费失败不 ACK，进入重试队列
