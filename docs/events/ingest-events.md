# Ingest Service 事件契约

## 概述

Ingest 服务消费 Knowledge 服务发布的领域事件，并发布自己的状态反馈事件。

## 消费的事件（来自 Knowledge Service）

| 事件类型 | 说明 | 动作 |
| --- | --- | --- |
| `knowledge.document.registered` | 文档已登记 | 创建 IMPORT 任务 |
| `knowledge.document.reingest-requested` | 请求重新入库 | 创建 REIMPORT 任务 |
| `knowledge.document.deleted` | 文档已删除 | 创建 DELETE 任务，取消待处理任务 |

## 发布的事件（状态反馈）

| 事件类型 | 说明 | 触发时机 |
| --- | --- | --- |
| `ingest.document.processing` | 任务开始处理 | 任务进入 PROCESSING 状态 |
| `ingest.document.dispatched` | 任务已分发到 RAG | 任务进入 DISPATCHED 状态 |
| `ingest.document.succeeded` | 任务成功完成 | 任务进入 SUCCEEDED 状态 |
| `ingest.document.failed` | 任务失败 | 任务进入 FAILED/DEAD 状态 |
| `ingest.document.deleted` | 文档已清理 | 删除任务完成 |

## 事件契约定义

```java
public record IngestEvent(
    UUID eventId,           // 唯一事件标识
    String eventType,       // 事件类型
    String aggregateType,   // 聚合类型 (ingest_task)
    String aggregateId,     // 聚合ID (任务ID)
    Long knowledgeBaseId,   // 知识库ID
    Long documentId,        // 文档ID
    String ingestStatus,    // 入库状态
    Long operatorId,        // 操作者用户ID
    int schemaVersion,      // 事件schema版本
    Instant occurredAt      // 发生时间
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

## RAG 占位事件（未来实现）

以下事件定义了 ingest-service 与 rag-service 的边界：

| 事件类型 | 说明 |
| --- | --- |
| `rag.document.ingest.requested` | 请求 RAG 处理文档 |
| `rag.document.delete.requested` | 请求 RAG 删除文档向量 |
| `rag.document.ingest.completed` | RAG 处理完成 |
| `rag.document.ingest.failed` | RAG 处理失败 |

## Outbox 状态流转

```
PENDING → PUBLISHED (成功)
        → FAILED (失败，可重试)
        → DEAD (超过最大重试次数)
```

### 重试策略

| 重试次数 | 延迟 |
| --- | --- |
| 1 | 30 秒 |
| 2 | 5 分钟 |
| 3 | 30 分钟 |

超过 3 次重试后进入 DEAD 状态。
