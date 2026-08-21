# 企业知识库微服务版架构概览

## 目标与边界

本工程把旧版“Java 模块化单体 + Python RAG”演进为 6 个独立部署单元。Maven 多模块只负责
统一构建和版本，不改变运行时边界；每个 Java 服务拥有独立启动类、配置、镜像、端口、健康
检查和部署生命周期。

```text
Web
  |
Gateway (WebFlux)
  |---- iam-service -------- docbase_iam / Redis
  |---- knowledge-service -- docbase_knowledge / MinIO / Outbox
  |---- ingest-service ----- docbase_ingest / RabbitMQ
  `---- chat-service ------- docbase_chat
                                  |
                                  `---- rag-service -- docbase_rag / Chroma
```

所有 Java 服务注册到 Nacos `docbase-dev` Namespace、`DOCBASE_GROUP` Group。短同步调用使用
OpenFeign，长连接 SSE 使用 WebClient。Gateway 只负责统一入口和初步认证，业务服务仍须独立
验签和授权。

## 服务职责

| 服务 | 数据所有权 | 当前阶段 |
| --- | --- | --- |
| gateway-service | 无业务库 | 路由、CORS、Trace ID、错误响应 |
| iam-service | `docbase_iam` | 骨架与探针；后续迁移认证及 `sys_*` |
| knowledge-service | `docbase_knowledge` | Flyway Outbox 基线与 MinIO 配置 |
| ingest-service | `docbase_ingest` | 导入任务、消费去重表和 RabbitMQ 配置 |
| chat-service | `docbase_chat` | 会话表基线、Feign 与 WebClient |
| rag-service | `docbase_rag`、Chroma | 容器健康占位，不含完整 RAG |

`common-*` 仅提供跨服务技术能力。禁止把业务实体、Mapper、Repository 或业务 Service 放入
公共库；跨服务共享的数据只能通过 API DTO 或版本化事件契约表达。

## 关键运行链路

### 同步请求

客户端请求经 Gateway 使用 Nacos 服务发现和 `lb://` 路由到业务服务。每个请求生成或透传
`X-Trace-Id`，Gateway 先删除所有客户端传入的 `X-User-*` 头。IAM 迁移后，Gateway 和每个
业务服务都用 IAM 公钥独立验证 JWT。

### 文档最终一致性

Knowledge 在同一本地事务中修改文档并写 `event_outbox`。发布器将事件可靠发送到
RabbitMQ；Ingest 通过 `processed_event(event_id)` 和业务唯一约束幂等消费，再调用 RAG。
失败进入 30 秒、5 分钟重试队列，最终进入 DLQ。更新场景先成功构建新向量再切换活动版本，
删除场景先从权限查询中排除，再异步清理向量。

### 权限下沉 RAG

Chat 根据当前身份向 Knowledge 查询可见文档 ID，再把 `visible_doc_ids` 传给 RAG。RAG 在
Chroma 检索阶段过滤 chunk，无权限内容不进入 Prompt。该链路不会在基础工程阶段伪实现。

## 配置与秘密

Nacos 保存超时、功能开关、限流和 RAG 参数；数据库密码、Nacos 密码、RabbitMQ 密码、
MinIO Secret、JWT 私钥、内部 API Key 和聊天模型 API Key 仅通过环境变量或后续 Docker
Secrets 注入。仓库只包含 `.env.example` 占位值。

Java 使用 `spring.config.import` 加载 `common.yaml` 与服务级 Data ID，不使用
`bootstrap.yml`、`shared-configs` 或 `extension-configs`。

## 本地部署

Compose 项目名固定为 `docbase-ms`，不设置 `container_name`。基础设施、应用、治理和观测
分别由 profiles 控制。所有持久组件使用命名卷；停止脚本不会删除卷。MySQL 与 Redis 不映射
宿主机端口，业务服务仅通过 Gateway 暴露。
