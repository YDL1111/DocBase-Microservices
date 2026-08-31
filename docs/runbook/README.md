# 本地运行手册

本文以 Windows PowerShell 和 Docker Desktop 为例。日常操作优先使用仓库 `scripts` 下的脚本，避免遗漏 Compose 文件、Profile 或初始化任务。

## 环境要求

- Windows 10/11 与 PowerShell 5.1 以上；
- Docker Desktop，支持 Docker Compose v2；
- JDK 21；
- Git；
- 如需本地运行或测试前端，安装 Node.js 20 以上。

## 首次配置

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，至少替换所有带 `change-me` 的密码、JWT 密钥、Nacos 认证信息和内部 API Key。真实 `.env` 已被 Git 忽略，不要把它粘贴到 Issue、日志或提交记录中。

可以使用兼容 PowerShell 5.1 的方式生成 32 字节随机 Base64 密钥：

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[Convert]::ToBase64String($bytes)
```

不同用途应分别生成密钥，不要让 JWT、内部服务和第三方模型共用同一个值。

## 启动顺序

### 启动基础设施

```powershell
.\scripts\start-infra.ps1
```

脚本会启动 MySQL、Redis、RabbitMQ、MinIO 和 Nacos，等待健康检查通过，然后以临时容器运行幂等初始化任务。初始化容器执行完即删除，因此 Docker Desktop 不会长期堆积已退出的容器。

### 构建并启动全部应用

```powershell
.\scripts\start-apps.ps1
```

该脚本先执行 Maven 校验，再确认基础设施和初始化任务，最后构建并启动 Gateway、业务服务、RAG 与 Web。

如果代码和镜像已经构建，只想快速启动：

```powershell
.\scripts\start-apps.ps1 -SkipBuild
```

## 常用地址

| 服务 | 地址 |
| --- | --- |
| Web | <http://localhost:3000> |
| Gateway | <http://localhost:8080> |
| Nacos | <http://localhost:8848/nacos> |
| RabbitMQ 管理台 | <http://localhost:15672> |
| MinIO 控制台 | <http://localhost:9001> |
| 本地 MySQL | `127.0.0.1:3309` |

浏览器业务请求只访问 Web 和 Gateway。MySQL 的 `3309` 映射来自 `deploy/compose.dev.yml`，用于本机数据库工具连接，不代表容器内部端口改变。

## 查看状态

```powershell
.\scripts\status.ps1
```

脚本会显示所有容器，并检查 Gateway、IAM 路由、Nacos、RabbitMQ、MinIO 和 Web。

查看单个服务日志：

```powershell
docker compose --env-file .env `
  -f deploy/compose.yml `
  -f deploy/compose.dev.yml `
  --profile infrastructure `
  --profile application logs --tail 200 chat-service
```

## 停止与再次启动

日常关闭使用：

```powershell
.\scripts\stop.ps1
```

该命令只停止容器，保留容器、网络和命名卷。下次可以运行启动脚本，也可以在 Docker Desktop 中启动 `docbase-ms` 项目。

需要移除容器和项目网络时使用：

```powershell
.\scripts\down.ps1
```

`down.ps1` 仍会保留命名卷。不要随意增加 `-v`，否则会删除 MySQL、MinIO、RabbitMQ、Nacos 和向量库数据。

## 只重建某个服务

Compose 合并文件必须同时提供完整 Profile，避免出现“依赖了未定义服务”：

```powershell
docker compose --env-file .env `
  -f deploy/compose.yml `
  -f deploy/compose.dev.yml `
  --profile infrastructure `
  --profile application `
  up -d --build --force-recreate --no-deps --wait web
```

将最后的 `web` 替换为 `iam-service`、`chat-service` 等服务名即可。仅当前账户无权连接 Docker daemon 时才需要管理员 PowerShell；正常情况下不应依赖管理员权限。

## 验证工程

完整验证：

```powershell
.\scripts\verify.ps1
```

只执行编译、测试、Compose 配置检查和敏感信息扫描，不启动容器：

```powershell
.\scripts\verify.ps1 -SkipContainerStart
```

前端可单独执行：

```powershell
cd web
npm ci
npm run test
npm run typecheck
npm run build
```

## 常见问题

### Web 容器不健康

先查看 Web 日志和健康状态，不要立即删除卷：

```powershell
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.dev.yml `
  --profile infrastructure --profile application ps web
docker logs docbase-ms-web-1 --tail 200
```

如果只是前端代码未进入镜像，重新构建 `web` 后使用 `Ctrl+F5` 清理浏览器缓存。

### Maven 下载依赖失败

`Remote host terminated the handshake` 通常是 Maven Central 网络或 TLS 连接问题。确认代理、DNS 和系统时间后重试；依赖已存在本地仓库时可以使用离线模式，但首次构建不能依赖 `-o`。

### 环境变量修改后没有生效

修改 `.env` 后需要重新创建使用该变量的容器。数据库初始化密码只在新数据卷首次创建时生效，修改 `.env` 不会自动重置已有 MySQL 用户密码。

### 页面菜单跳转 404

先重新构建 Web 并强制刷新。如果菜单仍可见但页面是 404，检查 IAM 返回的 `path`、`routerName` 是否与前端组件注册一致，并确认动态路由已经在登录后加载。

### 文档导入失败或等待重试

按顺序查看 Knowledge、RabbitMQ、Ingest 和 RAG 日志。重点核对文档对象是否存在、事件是否投递、任务状态转换、内部 API Key 是否一致，以及 Embedding/向量库是否可用。任务显示成功后不应继续展示旧错误信息。
