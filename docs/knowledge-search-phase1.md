# 知识检索第一阶段

知识检索返回已索引文档的相关文本片段，不生成回答。复用 RAG 的 embedding、Chroma 和排序，无新增中间件或数据库迁移。

## 接口

`POST /api/knowledge/search`

请求：`{"knowledgeBaseId": 2, "query": "部署步骤"}`。query 非空且最长 500 字符。需要 `knowledge:document:list` 或 `admin:all` 权限，并通过知识库访问校验。

响应沿用 ApiResponse，data 为最多 10 条片段数组：documentId、title、filename、content、page、sheet、slide、heading。位置字段仅在索引中存在时返回；不提供伪造页码或全文结果总数。

服务器计算可见文档 ID，仅检索已发布、索引成功、未删除文档；检索完成后重新检查范围，并使用数据库中的标题、文件名。客户端提交的文档 ID 列表不会用于授权。原文下载走现有受保护 content 接口。

第一版受现有 RAG scope 限制：单库超过 1000 份可检索文档时明确报 `SEARCH_SCOPE_TOO_LARGE`，不会静默截断。后续需要分批召回与统一排序，或在向量存储中实现可扩展的权限过滤。

## 配置与启动

knowledge-service 新增使用 `RAG_BASE_URL`（默认 http://rag-service:8090）和 `RAG_INTERNAL_API_KEY`。Compose 已传入与 RAG 一致的内部密钥。独立启动 Java 服务时须显式配置可达 URL 和相同密钥。

Docker 引擎启动后在项目根目录执行：

```powershell
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.dev.yml --profile infrastructure --profile application up -d --build --no-deps knowledge-service web
```

该命令仅更新两项服务，要求其他依赖服务已运行。现有 RAG 服务无需代码更新，已有索引可直接使用。

## 验收

1. 登录并打开知识检索，选择知识库，输入与已索引文档相关的描述。
2. 查看返回片段、真实页码/章节（若有），下载原文并核对。
3. 普通用户不能检索无权访问的知识库或文档。
4. 无结果与服务错误分别展示；修改查询或知识库后旧请求结果不得覆盖当前页面。
5. 删除或撤销文档访问权限后不再返回该文档，下载接口仍独立鉴权。

测试覆盖 HTTP 认证/权限/输入验证、客户端范围注入、服务端范围过滤与撤权、内部请求协议、索引上下文前缀剥离、超限范围，以及前端异步/错误/空态/XSS 文本渲染。
