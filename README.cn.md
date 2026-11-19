<h1><img src="images/mindrails-logo.svg" width="290" alt="MindRails"></h1>

![Stage: MVP](https://img.shields.io/badge/stage-MVP-9b6615) ![Java 21](https://img.shields.io/badge/Java-21-356683) [![MIT license](https://img.shields.io/badge/license-MIT-347350)](LICENSE)

[English](README.md)

**面向团队的自托管 Agent 平台。** 管理员接好模型，同事就能在浏览器里查资料、处理文件、做报告。

- **控制成本。** 团队共用一套服务，日常任务可选低价模型，还能按模型查看 Token 用量。
- **自由选模型。** 每个会话可选对话模型，每个知识库可选嵌入模型。支持多家供应商，也可以接入兼容 OpenAI 的对话端点。
- **按需定制。** 在自己的基础设施上部署，按 MIT 许可修改源码、接入内部工具。

## 能做什么

- 给 **Agent** 设定职责，为每项任务单开**会话**，随时回来查看对话和文件。
- 接入**知识库**，让回答引用自己的资料。展开**工具调用**，查看 Agent 如何得到结果。
- 把常用流程打包为**技能**，或通过 **MCP** 调用其他服务的工具。

参阅[图文介绍](Introduction.cn.md)，看一项调研任务如何从问题变成演示文稿。

## 开发指南

Java 21 / Spring Boot 后端位于 `src/main/java/com/mindrails/backend/`。运行配置见 `src/main/resources/application.properties`，测试见 `src/test/`，数据库脚本见 `db/`。网页客户端使用独立的[前端仓库](https://github.com/patternrails/mindrails-frontend/tree/main)。

### 本地体验完整平台

安装 Docker Compose v2.30+ 和 OpenSSL，在仓库根目录启动前端、后端、MySQL 和 Qdrant。

```bash
./db/setup-compose.sh
docker compose up -d --wait
```

打开 [MindRails](http://localhost:3000)，用 `admin` 和 `.env` 中生成的 `MINDRAILS_ADMIN_PASSWORD` 登录。在 **Settings → Providers** 添加默认对话模型。需要语义检索时再添加嵌入模型。

### 用 Gradle 开发

使用 JDK 21。如果完整平台已启动，先用 `docker compose stop backend frontend` 释放应用端口。接下来启动数据库、加载生成的环境变量并运行后端。

```bash
./db/setup-compose.sh
docker compose up -d --wait mysql qdrant
set -a
. ./.env
set +a
export MINDRAILS_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/mindrails
export MINDRAILS_QDRANT_HOST=127.0.0.1
./gradlew bootRun
```

API 监听 8080 端口。前端在其仓库中启动，后端测试使用 `./gradlew test`。

### 用 Docker 部署

建议使用独立的 MySQL 8.4 和 Qdrant 服务。创建 `utf8mb4` 编码的 `mindrails` 库及专用应用账号。用有权创建和修改表、存储过程的迁移账号初始化数据库。请替换示例中的主机名和账号。

```bash
cat db/lock.sql db/schema.sql db/upgrade.sql db/bootstrap.sql | mysql -h mysql.example.org -u migration_user -p mindrails
```

复制 `backend.env.example` 为仅管理员可读的 `production.env`，启动容器前完成以下配置。

- **存储。** 用应用账号配置 `MINDRAILS_MYSQL_URL`、`MINDRAILS_MYSQL_USERNAME` 和 `MINDRAILS_MYSQL_PASSWORD`。用 Qdrant HTTP 地址配置 `MINDRAILS_QDRANT_HOST` 和 `MINDRAILS_QDRANT_PORT`。按需设置 `MINDRAILS_QDRANT_TLS` 和 `MINDRAILS_QDRANT_API_KEY`。
- **凭据。** 设置 `MINDRAILS_ADMIN_LOGIN_ID` 和 12–128 字符的 `MINDRAILS_ADMIN_PASSWORD`。用 `openssl rand -base64 32` 生成一次 `MINDRAILS_PROVIDER_ENCRYPTION_KEY`，升级和恢复时沿用该密钥。
- **网页和文件。** 将 `MINDRAILS_ALLOWED_ORIGINS` 设为前端 HTTPS 来源，并设 `MINDRAILS_SECURE_COOKIE=true`、`MINDRAILS_FILE_STORAGE_DIRECTORY=/opt/mindrails/data`。备份文件卷、MySQL、Qdrant 和加密密钥。

```bash
docker run -d --name mindrails-backend --restart unless-stopped \
  --env-file production.env -p 127.0.0.1:8080:8080 \
  -v mindrails-files:/opt/mindrails/data haarp/mindrails-backend:latest

docker run -d --name mindrails-frontend --restart unless-stopped \
  -e MINDRAILS_BACKEND_BASE_URL=https://api.example.org \
  -p 127.0.0.1:3000:3000 haarp/mindrails-frontend:latest
```

把 `https://api.example.org` 换成浏览器可访问的 API 地址。HTTPS 反向代理将前端请求转发到 3000 端口，API 请求转发到 8080 端口，并支持 WebSocket 升级。用 `curl -fsS http://127.0.0.1:8080/api/health` 检查服务状态。

### 升级数据库

Compose 自动初始化新数据卷。升级已有数据库时，先停止后端并备份，再执行迁移。

```bash
docker compose stop backend
docker compose up -d --wait mysql
./db/db.sh migrate
docker compose up -d --wait
```

脚本用于本地 `mindrails-mysql` 容器。外部 MySQL 请用迁移账号执行上面的 SQL 管道命令。

已有安装沿用原配置。如果曾使用旧的开发默认值，请在部署前轮换 MySQL 密码，并用新密钥重新加密已保存的供应商凭据。

MindRails 处于 MVP 阶段。欢迎来信讨论：[yuchen.zhang.mail@gmail.com](mailto:yuchen.zhang.mail@gmail.com)。

[LICENSE](LICENSE) — 本仓库采用 MIT 许可证。
