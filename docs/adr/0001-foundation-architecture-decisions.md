# ADR-0001：微服务基础架构决策

- 状态：Accepted
- 日期：2026-07-31
- 范围：企业知识库微服务版基础工程

## 背景

旧项目已经具备可工作的 Java 业务系统和独立 Python RAG。新工程的目标是按业务边界渐进
迁移，同时保留旧项目作为稳定基线，避免一次性拆分破坏权限、文件、导入和向量同步链路。

## 决策

### 1. 合并 IAM

认证、用户、角色、菜单、部门和系统配置共同拥有 `sys_*` 主数据，统一归属 `iam-service`。
如果再拆 `auth-service` 与 `system-service`，会造成同表多所有者、循环调用或数据复制。待
规模和团队边界确实要求时再评估拆分。

### 2. 单 MySQL 实例、多 Schema

本地只运行一个 MySQL 8.4 以控制资源，但每个服务拥有独立 Schema、账号和 Flyway。授权从
机制上禁止跨服务查表，未来可在不改变数据归属的情况下迁移到独立实例。

### 3. 使用 Nacos

Nacos 同时提供注册发现和集中配置，适配 Spring Cloud Alibaba 技术线，也支持本地单节点
演示服务注册、`lb://` 负载均衡和配置刷新。开发 Namespace 固定为 `docbase-dev`，Group
固定为 `DOCBASE_GROUP`；生产环境应改为高可用集群。

### 4. 不使用 Seata

关键一致性边界跨越 MySQL、RabbitMQ、MinIO、Python 与向量库，Seata 无法把这些资源自然
纳入同一强事务。采用本地事务、Transactional Outbox、幂等消费、状态机、重试和补偿任务，
明确接受并治理最终一致性。

### 5. 保留 Chroma

旧 RAG 已使用 Chroma，当前本地数据规模和演示目标不需要 Milvus 的额外部署成本。微服务
迁移不同时更换向量引擎，降低变量数量；迁移后从 MinIO 原始文档重建新索引，不复用旧卷。

### 6. 使用 Outbox 保证最终一致性

Knowledge 在同一本地事务中保存业务变更和 `event_outbox`，后台发布器通过 publisher
confirm/return 投递持久化消息。Ingest 使用 `event_id` 去重和业务唯一约束，只有业务事务
成功后才 ACK。该方案能覆盖数据库提交后 RabbitMQ 暂不可用以及消息重复投递。

## 后果

- 优点：本地资源可控，数据所有权清晰，故障可恢复，适合逐阶段迁移和面试演示。
- 代价：系统接受短暂不一致，需要可观测的状态机、补偿任务、DLQ 和人工重试 runbook。
- 约束：禁止跨 Schema 查询，禁止将业务模型放入 common 模块，禁止把敏感配置放入 Nacos
  模板或 Git。
