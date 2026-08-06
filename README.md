# DocBase Microservices

企业知识库微服务版是现有 DocBase 项目的第二版架构工程。当前仓库聚焦“基础工程初始化和
基础设施可运行”，保留旧项目作为业务参考，不在本阶段大规模迁移 Java 业务、Vue 页面或
完整 Python RAG。

## 架构组件

- Java 21、Spring Boot 3.5.16、Spring Cloud 2025.0.3
- Spring Cloud Alibaba 2025.0.0.0、Nacos Server 3.1.1
- Spring Cloud Gateway WebFlux、OpenFeign、WebClient
- Spring Security 6、MyBatis-Plus 3.5.17、Flyway、HikariCP
- MySQL 8.4、Redis 7.4 Alpine、RabbitMQ 4.2 Management Alpine
- MinIO 固定 2025 Release、Chroma（后续 RAG 迁移继续保留）
- Sentinel Dashboard（`governance` profile）
- Prometheus、Grafana、Zipkin（`observability` profile）

## 目录说明

```text
services/       5 个独立 Spring Boot 应用
libraries/      纯技术公共库与事件契约，不含共享业务模型
rag-service/    Python FastAPI RAG 服务（文档解析、Embedding、Chroma、检索、SSE）
web/            Vue 迁移前的 Nginx 占位
deploy/         Compose、Nacos、Redis、RabbitMQ、MinIO、治理与观测配置
database/       MySQL 多 Schema、独立账号和 Nacos 官方表结构初始化
docs/           架构、ADR、API、事件、迁移和运行手册
scripts/        Windows PowerShell 启停与验收脚本
```

## 服务与端口

| 组件 | 容器端口 | 宿主机端口 | 说明 |
| --- | ---: | ---: | --- |
| Web placeholder | 80 | 3000 | 唯一前端入口 |
| gateway-service | 8080 | 8080 | 唯一业务 API 入口 |
| iam-service | 8081 | 不暴露 | IAM 合并服务 |
| knowledge-service | 8082 | 不暴露 | 知识与对象元数据 |
| ingest-service | 8083 | 不暴露 | 导入和同步任务 |
| chat-service | 8084 | 不暴露 | AI 会话与 SSE 编排 |
| rag-service | 8090 | 不暴露 | 本阶段仅健康占位 |
| Nacos API | 8848 | 8848 | 注册与配置 API |
| Nacos Console | 8080 | 18080 | Nacos 3 控制台 |
| RabbitMQ Management | 15672 | 15672 | 消息管理控制台 |
| MinIO API / Console | 9000 / 9001 | 9000 / 9001 | 对象存储与控制台 |
| Sentinel | 8858 | 8858 | governance profile |
| Prometheus | 9090 | 9090 | observability profile |
| Grafana | 3000 | 3001 | observability profile |
| Zipkin | 9411 | 9411 | observability profile |

MySQL、Redis、RabbitMQ AMQP 以及所有业务服务端口只在 Compose 网络内开放。

## 环境要求

- Windows 11 / Windows Server，PowerShell 7 或 Windows PowerShell 5.1
- Java 21（项目强制编译目标为 21）
- Docker Desktop 与 Docker Compose v2
- Git

仓库包含 Maven Wrapper 3.9.11，不要求全局安装 Maven。本机若存在较新的 JDK，也必须能够
使用 `--release 21`；推荐仍将 `JAVA_HOME` 指向 JDK 21。

## 环境变量

只提交了 `.env.example`。首次运行可复制为 `.env` 并替换全部 `change-me-*` /
`replace-with-*` 占位值：

```powershell
Copy-Item .env.example .env
```

`.env` 已被忽略，禁止复制旧仓库中的真实 API Key、数据库密码或 JWT 私钥到受版本控制文件。
DeepSeek 模型名称继续使用旧项目当前配置，Embedding 固定为 `BAAI/bge-m3`，不下载新模型；
后续 RAG 容器复用本机已有 HuggingFace 缓存。

## 启动命令

启动基础设施：

```powershell
.\scripts\start-infra.ps1
```

编译测试并启动全部应用：

```powershell
.\scripts\start-apps.ps1
```

按需启用治理或观测：

```powershell
docker compose -f deploy/compose.yml -f deploy/compose.dev.yml --profile governance up -d
docker compose -f deploy/compose.yml -f deploy/compose.dev.yml --profile observability up -d
```

查看状态或停止（命名卷会保留）：

```powershell
.\scripts\status.ps1
.\scripts\stop.ps1
```

一键执行编译、测试、Compose 校验、基础设施和 Gateway/IAM 冒烟：

```powershell
.\scripts\verify.ps1
```

## 健康检查

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/auth/ping
Invoke-RestMethod http://localhost:8848/nacos/v1/ns/operator/metrics
Invoke-RestMethod http://localhost:9000/minio/health/live
```

第二个请求必须由 Gateway 通过 `lb://iam-service` 转发并返回
`data.service = "iam-service"`。

## 数据边界

本地只有一个 MySQL 8.4 容器，但初始化 `nacos_config`、`docbase_iam`、
`docbase_knowledge`、`docbase_ingest`、`docbase_chat`、`docbase_rag` 六个 Schema。
每个账号只获得自己的 Schema 权限。Java 服务 Flyway 迁移位于各自
`src/main/resources/db/migration`，Hikari `maximum-pool-size` 均为 5。

## 常见问题

### 默认 Java 是 17

设置当前会话的 `JAVA_HOME` 为 JDK 21 后再运行 Wrapper。项目 Enforcer 会拒绝低于 21 的
JDK，避免误用 Java 17 构建。

### Nacos 首次启动较慢

首次启动会等待 MySQL 初始化并创建管理员、Namespace 和配置，通常需要 1～2 分钟。使用
`docker compose ... logs nacos nacos-init` 查看进度。若 `nacos-init` 因密码初始化失败，
可清空仅属于本项目的 `docbase-ms_mysql-data` 卷后重试；不要操作旧项目卷。

### 修改初始化密码后服务无法连接

MySQL、Nacos 等初始化账号只在空数据卷时创建。已有卷不会因修改 `.env` 自动改密，应通过
管理接口变更密码，或在确认无需保留本项目数据后单独重建对应 `docbase-ms` 卷。

### Gateway 返回 502

先检查 IAM 健康状态以及它是否注册在 `docbase-dev` Namespace、
`DOCBASE_GROUP` Group，再检查 Gateway 与 IAM 使用的 Namespace ID 是否一致。

### 端口冲突

Gateway 使用宿主机 8080；Nacos 3 控制台映射到 18080，避免与 Gateway 冲突。

## 当前阶段完成内容

- Maven 多模块与统一 BOM
- 5 个独立 Spring Boot 应用、独立 Dockerfile 和最小上下文测试
- Gateway 的 4 条固定 `lb://` 路由
- Nacos Discovery/Config 的 `spring.config.import` 接入和 6 个配置模板
- 单 MySQL 多 Schema/账号、服务独立 Flyway、Redis ACL、RabbitMQ 拓扑、MinIO 受限用户
- infrastructure/application/governance/observability Compose profiles
- RAG、Web 占位与 PowerShell 运维脚本
- Actuator、Prometheus 指标、统一响应/异常、Trace ID 日志

当前安全骨架已替换为真实 IAM 能力：非对称 RS256 JWT 签发/验签、Redis 登录态
管理（Refresh Token 轮换、会话版本、密码修改失效）、BCrypt 密码编码、基于权限
字符串的方法级授权。Gateway 使用公钥做初步认证，iam-service 使用私钥签发并独立
验签。详见 [IAM API 文档](docs/api/iam.md) 和 [迁移文档](docs/migration/iam-migration.md)。

**Knowledge 业务迁移已完成**：知识库管理、目录树、文档元数据、成员管理、权限控制、
Outbox 事件。详见 [Knowledge API 文档](docs/api/knowledge.md) 和
[迁移文档](docs/migration/knowledge-migration.md)。

## 下一阶段迁移顺序

1. **IAM（已完成）**：迁移 `sys_*`、登录态、非对称 JWT 和权限缓存。
2. **Knowledge（已完成）**：迁移知识库、目录、文档元数据、成员、权限和 Outbox。
3. **Ingest（已完成）**：实现 Outbox 发布、RabbitMQ 幂等消费、任务状态机、状态反馈事件。
4. RAG：迁移现有 FastAPI/Chroma/DeepSeek/bge-m3 链路并接入 Nacos。
5. Chat：迁移会话、`visible_doc_ids` 权限下沉与 WebClient SSE。
6. 前端与 Agent：复用 Vue 页面，最后迁移管理员 Agent 工具。

更多设计见 [架构概览](docs/architecture/overview.md) 和
[ADR-0001](docs/adr/0001-foundation-architecture-decisions.md)。
