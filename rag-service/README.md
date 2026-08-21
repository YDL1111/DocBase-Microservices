# rag-service（迁移占位）

本阶段不迁移旧 Python RAG 业务代码，仅提供可构建、可健康检查的容器占位和后续配置契约。

- 运行端口：`8090`
- 健康检查：`GET /health`
- 聊天模型：通过 `CHAT_API_KEY`、`CHAT_BASE_URL` 和 `CHAT_MODEL` 接入任意
  OpenAI-compatible 服务；仓库不保存真实值。
- Embedding：固定 `BAAI/bge-m3`。
- HuggingFace 缓存：后续通过只读/命名卷挂载本机已有缓存，不在此阶段下载模型。
- 向量库：保留 Chroma，不引入 Milvus。
- 数据库：`docbase_rag`，仅允许 `docbase_rag` 应用账号访问。
- 服务治理：正式迁移时在 FastAPI 生命周期中加入 Nacos 3 注册、续约和注销。

`placeholder.py` 只用于 Compose 连通性与健康检查，不包含解析、切片、Embedding、检索或生成逻辑。
