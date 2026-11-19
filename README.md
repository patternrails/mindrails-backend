<h1><img src="images/mindrails-logo.svg" width="290" alt="MindRails"></h1>

![Stage: MVP](https://img.shields.io/badge/stage-MVP-9b6615) ![Java 21](https://img.shields.io/badge/Java-21-356683) [![MIT license](https://img.shields.io/badge/license-MIT-347350)](LICENSE)

[简体中文](README.cn.md)

**A self-hosted agent platform for teams.** One administrator connects your models. Everyone else signs in through a browser to research a question, work with files or prepare a report.

- **Control costs.** Share one installation. Use a cheaper model for routine tasks and track token usage by model.
- **Choose your providers.** Select a chat model per session and an embedding model for each knowledge base. Connect a supported provider or an OpenAI-compatible chat endpoint.
- **Make it yours.** Run MindRails on your infrastructure. Adapt the source and connect internal tools under the MIT license.

## What you can do

- Give an **agent** a role. Start a **session** for each assignment and return to its files and conversation.
- Connect a **knowledge base** so answers cite your documents. Inspect **tool calls** to see how the agent got its result.
- Package a workflow as a **skill**, or use **MCP** to connect tools from another service.

Follow a research task from question to slide deck in the [illustrated introduction](Introduction.md).

## Developer guide

The Java 21 / Spring Boot backend is in `src/main/java/com/mindrails/backend/`. Runtime settings are in `src/main/resources/application.properties`, tests in `src/test/` and database scripts in `db/`. The web client has its own [frontend repository](https://github.com/patternrails/mindrails-frontend/tree/main).

### Try the full stack locally

From the repository root, use Docker Compose v2.30+ and OpenSSL to start the web client, backend, MySQL and Qdrant.

```bash
./db/setup-compose.sh
docker compose up -d --wait
```

Open [MindRails](http://localhost:3000). Sign in as `admin` with the generated `MINDRAILS_ADMIN_PASSWORD` in `.env`. Add a default chat model in **Settings → Providers**. Add an embedding model to enable semantic search.

### Develop with Gradle

Use JDK 21. If the full stack is running, free the application ports with `docker compose stop backend frontend`. Start the databases, load the generated environment and run the backend.

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

The API listens on port 8080. Start the frontend from its repository. Run backend tests with `./gradlew test`.

### Deploy with Docker

Use external MySQL 8.4 and Qdrant services. Create the `mindrails` database with `utf8mb4` and a dedicated application account. Apply the schema with a migration account that can create and alter tables and routines. Replace the example hosts and accounts with yours.

```bash
cat db/lock.sql db/schema.sql db/upgrade.sql db/bootstrap.sql | mysql -h mysql.example.org -u migration_user -p mindrails
```

Copy `backend.env.example` to a private `production.env`. Configure the following before starting the containers.

- **Storage.** Set `MINDRAILS_MYSQL_URL`, `MINDRAILS_MYSQL_USERNAME` and `MINDRAILS_MYSQL_PASSWORD` for the application account. Set `MINDRAILS_QDRANT_HOST` and `MINDRAILS_QDRANT_PORT` to the Qdrant HTTP endpoint. Configure `MINDRAILS_QDRANT_TLS` and `MINDRAILS_QDRANT_API_KEY` if required.
- **Credentials.** Set `MINDRAILS_ADMIN_LOGIN_ID` and a 12–128 character `MINDRAILS_ADMIN_PASSWORD`. Generate `MINDRAILS_PROVIDER_ENCRYPTION_KEY` once with `openssl rand -base64 32`. Keep this key for upgrades and restores.
- **Web and files.** Set `MINDRAILS_ALLOWED_ORIGINS` to the frontend HTTPS origin, `MINDRAILS_SECURE_COOKIE=true` and `MINDRAILS_FILE_STORAGE_DIRECTORY=/opt/mindrails/data`. Back up this volume with MySQL, Qdrant and the encryption key.

```bash
docker run -d --name mindrails-backend --restart unless-stopped \
  --env-file production.env -p 127.0.0.1:8080:8080 \
  -v mindrails-files:/opt/mindrails/data haarp/mindrails-backend:latest

docker run -d --name mindrails-frontend --restart unless-stopped \
  -e MINDRAILS_BACKEND_BASE_URL=https://api.example.org \
  -p 127.0.0.1:3000:3000 haarp/mindrails-frontend:latest
```

Use a browser-accessible API URL in place of `https://api.example.org`. Route HTTPS traffic to port 3000 for the web client and port 8080 for the API. Enable WebSocket upgrades on the API route. Check health with `curl -fsS http://127.0.0.1:8080/api/health`.

### Upgrade a database

Compose initializes new volumes automatically. For an existing database, stop the backend and take a backup before migrating.

```bash
docker compose stop backend
docker compose up -d --wait mysql
./db/db.sh migrate
docker compose up -d --wait
```

The helper targets the local `mindrails-mysql` container. For external MySQL, run the SQL pipeline above with your migration account.

Existing installations keep their configured credentials. If you used the former development defaults, rotate the MySQL password and re-encrypt saved provider credentials before deployment.

MindRails is in MVP. Send questions or feedback to [yuchen.zhang.mail@gmail.com](mailto:yuchen.zhang.mail@gmail.com).

[LICENSE](LICENSE) — MIT license for this repository.
