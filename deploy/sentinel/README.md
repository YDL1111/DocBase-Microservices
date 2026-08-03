# Sentinel governance profile

`deploy/compose.yml` 的 `governance` profile 使用固定镜像启动 Sentinel Dashboard。
当前阶段仅提供控制台接入点；限流、慢调用熔断规则将在 AI 链路迁移后持久化到
Nacos 的 `docbase-dev` Namespace 与 `DOCBASE_GROUP` Group。
