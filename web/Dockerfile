# ---- Build stage ----
# 注意：构建上下文为项目根目录（compose.yml 中 context: ..），因此 COPY 路径带 web/ 前缀
FROM node:20-alpine AS builder
WORKDIR /app

# 安装依赖（利用 Docker 缓存：依赖不变时跳过）
# 复制 lockfile 并使用 npm ci 固定可复现构建
COPY web/package.json web/package-lock.json /app/
RUN npm ci

# 构建
COPY web/ /app/
RUN npm run build

# ---- Serve stage ----
FROM nginx:1.29.0-alpine
# 覆盖默认站点配置
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
# 拷贝构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
