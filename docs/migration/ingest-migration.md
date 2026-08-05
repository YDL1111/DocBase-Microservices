# Ingest 业务迁移文档

本文档说明从旧项目 `DocBase-Back-End`（AgileBoot 单体）到 `ingest-service` 的导入任务
业务迁移细节。

## 1. 旧项目导入流程分析

旧项目基于 AgileBoot 框架，导入流程采用以下结构：

- **入库任务**：`KnowledgeIngestTaskEntity` → `KnowledgeIngestTaskMapper`
- **任务服务**：`KnowledgeIngestTaskApplicationService`
- **Python RAG 客户端**：`PythonAiClient` → 调用 Python RAG 后端

任务类型：
- IMPORT (1)：首次导入
- REIMPORT (2)：重新导入
- RETRY (3)：手动重试
- DELETE (4)：删除同步

任务状态：
- PENDING (1)：待处理
- PROCESSING (2)：处理中
- SUCCESS (3)：成功
- FAILED (4)：失败

## 2. 新旧模型映射

### 表映射

| 旧表 | 新表 | 变化 |
| --- | --- | --- |
| `knowledge_ingest_task` | `ingest_task` | 重构，添加 event_id、next_retry_at |
| — | `consumed_event` | **新增**，幂等去重 |
| — | `ingest_outbox` | **新增**，状态反馈事件 |

### 关键设计变更

1. **事件驱动架构**：旧项目直接同步调用 Python RAG。
   新模型通过 RabbitMQ 事件驱动，实现解耦和可靠传递。

2. **Outbox 模式**：旧项目直接调用外部服务。
   新模型使用 Outbox 模式确保事件可靠发布。

3. **幂等消费**：旧项目通过查询状态判断是否处理。
   新模型通过数据库唯一约束（event_id）实现真正的幂等。

4. **状态机**：旧项目使用松散的状态判断。
   新模型定义明确的状态枚举和合法转换。

## 3. 数据库表结构

### ingest_task（入库任务表）

```sql
CREATE TABLE ingest_task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id          CHAR(36) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id       BIGINT NOT NULL,
    object_key        VARCHAR(512) NOT NULL DEFAULT '',
    task_type         VARCHAR(32) NOT NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count     INT NOT NULL DEFAULT 0,
    last_error        VARCHAR(512),
    next_retry_at     DATETIME,
    python_kb_id      VARCHAR(128),
    python_doc_id     VARCHAR(128),
    chunk_count       INT,
    created_by        BIGINT NOT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at        DATETIME,
    finished_at       DATETIME,
    UNIQUE KEY uk_ingest_event_id (event_id)
);
```

### consumed_event（已消费事件表）

```sql
CREATE TABLE consumed_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id         CHAR(36) NOT NULL,
    event_type       VARCHAR(128) NOT NULL,
    schema_version   INT NOT NULL DEFAULT 1,
    consumed_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result           VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    error_message    VARCHAR(512),
    UNIQUE KEY uk_consumed_event_id (event_id)
);
```

## 4. 任务状态机

```
PENDING → PROCESSING → DISPATCHED → SUCCEEDED
                  |            |
                  v            v
              FAILED       FAILED
                  |
                  v
             RETRY_WAIT
                  |
                  v
              DEAD (max retries)

PENDING/RETRY_WAIT → CANCELLED
```

### 状态说明

| 状态 | 说明 | 可转换到 |
| --- | --- | --- |
| PENDING | 待处理 | PROCESSING, CANCELLED |
| PROCESSING | 处理中 | DISPATCHED, SUCCEEDED, FAILED |
| DISPATCHED | 已分发到 RAG | SUCCEEDED, FAILED |
| SUCCEEDED | 成功完成 | (终态) |
| FAILED | 失败 | RETRY_WAIT, DEAD, CANCELLED |
| RETRY_WAIT | 等待重试 | PENDING, CANCELLED |
| DEAD | 超过最大重试次数 | (终态) |
| CANCELLED | 已取消 | (终态) |

## 5. 幂等消费机制

1. **唯一约束**：`consumed_event.event_id` 有唯一约束
2. **先查后插**：消费前先检查 eventId 是否已存在
3. **数据库兜底**：并发时唯一约束确保只有一条记录插入成功
4. **安全返回**：已完成事件再次到达时安全返回

## 6. 未迁移内容

以下内容将在后续阶段迁移：

- **文件内容解析**：属于 rag-service
- **PDF/Word/Excel 文本抽取**：属于 rag-service
- **Embedding 和向量存储**：属于 rag-service
- **Chroma 写入和查询**：属于 rag-service
- **完整 RAG 链路**：属于 rag-service
- **对话和 SSE**：属于 chat-service
