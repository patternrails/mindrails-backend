#!/usr/bin/env bash
set -euo pipefail

DB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="mindrails-mysql"
VOLUME="mindrails-mysql-data"
IMAGE="mysql:8.4"
LABEL="com.mindrails.service"
ENV_FILE="$DB_DIR/.env"

usage() {
    echo "Usage: $0 {start|stop|restart|status|logs|migrate}"
}

fail() {
    echo "Error: $*" >&2
    exit 1
}

container_exists() {
    docker container inspect "$CONTAINER" >/dev/null 2>&1
}

check_container() {
    local owner
    owner="$(docker container inspect --format '{{index .Config.Labels "com.mindrails.service"}}' "$CONTAINER")"
    [[ "$owner" == "mysql" ]] || fail "Container $CONTAINER is not managed by this script."
}

ensure_credentials() {
    "$DB_DIR/setup-compose.sh"
    # Read only the generated key; do not execute the env file as shell code.
    local password
    password="$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' "$ENV_FILE")"
    [[ "$password" =~ ^[0-9a-f]{48}$ ]] || fail "$ENV_FILE must contain one generated MYSQL_ROOT_PASSWORD (48 hex characters)."
    chmod 600 "$ENV_FILE"
}

load_application_credentials() {
    MYSQL_PASSWORD=${MINDRAILS_MYSQL_PASSWORD:-$(sed -n 's/^MINDRAILS_MYSQL_PASSWORD=//p' "$DB_DIR/../.env")}
    MYSQL_USER=${MINDRAILS_MYSQL_USERNAME:-$(sed -n 's/^MINDRAILS_MYSQL_USERNAME=//p' "$DB_DIR/../.env")}
    [[ -n "$MYSQL_PASSWORD" && -n "$MYSQL_USER" ]] || fail "Set the MySQL application credentials in .env."
    export MYSQL_PASSWORD MYSQL_USER
}

ensure_volume() {
    if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
        local owner
        owner="$(docker volume inspect --format '{{index .Labels "com.mindrails.service"}}' "$VOLUME")"
        [[ "$owner" == "mysql" ]] || fail "Volume $VOLUME is not managed by this script."
    else
        docker volume create --label "$LABEL=mysql" "$VOLUME" >/dev/null
    fi
}

wait_for_mysql() {
    local deadline=$((SECONDS + 240))
    local state
    echo "Waiting for MySQL and the application tables (up to 240 seconds)..."
    while ((SECONDS < deadline)); do
        state="$(docker container inspect --format '{{.State.Running}} {{.State.Health.Status}}' "$CONTAINER")"
        if [[ "$state" == "true healthy" ]]; then
            echo "MySQL is ready at 127.0.0.1:3306 (database: mindrails, user: mindrails_app)."
            return
        fi
        if [[ "$state" != true\ * ]]; then
            docker logs --tail 50 "$CONTAINER" >&2
            fail "MySQL exited during startup. Check $0 logs."
        fi
        sleep 2
    done
    docker logs --tail 50 "$CONTAINER" >&2
    fail "MySQL did not become ready within 240 seconds. Check $0 logs."
}

start_mysql() {
    if container_exists; then
        check_container
        docker start "$CONTAINER" >/dev/null
    else
        [[ -f "$DB_DIR/init.sql" ]] || fail "Missing $DB_DIR/init.sql."
        ensure_credentials
        docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull "$IMAGE"
        ensure_volume
        load_application_credentials
        docker run --detach \
            --name "$CONTAINER" \
            --label "$LABEL=mysql" \
            --restart unless-stopped \
            --publish 127.0.0.1:3306:3306 \
            --env-file "$ENV_FILE" \
            --env MYSQL_DATABASE=mindrails --env MYSQL_USER --env MYSQL_PASSWORD \
            --env TZ=UTC \
            --mount "type=volume,source=$VOLUME,target=/var/lib/mysql" \
            --mount "type=bind,source=$DB_DIR/init.sql,target=/docker-entrypoint-initdb.d/01-init.sql,readonly" \
            --mount "type=bind,source=$DB_DIR/schema.sql,target=/docker-entrypoint-initdb.d/02-schema.sql,readonly" \
            --mount "type=bind,source=$DB_DIR/upgrade.sql,target=/docker-entrypoint-initdb.d/03-upgrade.sql,readonly" \
            --mount "type=bind,source=$DB_DIR/bootstrap.sql,target=/opt/mindrails/bootstrap.sql,readonly" \
            --mount "type=bind,source=$DB_DIR/bootstrap.sh,target=/docker-entrypoint-initdb.d/04-bootstrap.sh,readonly" \
            --health-cmd='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP --host=127.0.0.1 --user=root --batch --skip-column-names --execute="SELECT 1 FROM mindrails.chat_memory LIMIT 0; SELECT 1 FROM mindrails.providers LIMIT 0; SELECT 1 FROM mindrails.agents LIMIT 0; SELECT 1 FROM mindrails.sessions LIMIT 0; SELECT 1 FROM mindrails.history LIMIT 0" >/dev/null' \
            --health-interval 5s \
            --health-timeout 5s \
            --health-start-period 30s \
            --health-retries 12 \
            "$IMAGE" \
            --character-set-server=utf8mb4 \
            --collation-server=utf8mb4_unicode_ci \
            --default-time-zone=+00:00 >/dev/null
    fi
    wait_for_mysql
}

[[ $# -eq 1 ]] || { usage >&2; exit 1; }
case "$1" in
    -h|--help|help) usage; exit 0 ;;
    start|stop|restart|status|logs|migrate) ;;
    *) usage >&2; exit 1 ;;
esac
command -v docker >/dev/null 2>&1 || fail "Docker CLI is not installed."
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable. Start Docker Desktop and retry."

case "$1" in
    start) start_mysql ;;
    stop)
        if container_exists; then
            check_container
            docker stop --time 30 "$CONTAINER" >/dev/null
            echo "MySQL stopped. Data is preserved in $VOLUME."
        else
            echo "MySQL container has not been created."
        fi
        ;;
    restart)
        if container_exists; then
            check_container
            docker restart --time 30 "$CONTAINER" >/dev/null
            wait_for_mysql
        else
            start_mysql
        fi
        ;;
    status)
        if container_exists; then
            check_container
            docker container inspect --format 'Container: {{.Name}} | State: {{.State.Status}} | Health: {{.State.Health.Status}}' "$CONTAINER"
        else
            echo "MySQL container has not been created."
        fi
        ;;
    migrate)
        container_exists || fail "MySQL container has not been created."
        check_container
        load_application_credentials
        # Keep the advisory lock, schema and bootstrap in one connection.
        {
            cat "$DB_DIR/lock.sql" "$DB_DIR/schema.sql" "$DB_DIR/upgrade.sql" "$DB_DIR/bootstrap.sql"
            echo "DO RELEASE_LOCK('mindrails.schema');"
        } | MYSQL_PWD="$MYSQL_PASSWORD" docker exec -i --env MYSQL_PWD "$CONTAINER" \
            mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER"
        echo "All schemas are ready. Existing data is preserved."
        ;;
    logs)
        container_exists || fail "MySQL container has not been created."
        check_container
        docker logs --tail 100 --follow "$CONTAINER"
        ;;
esac
