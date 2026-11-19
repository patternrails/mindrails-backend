#!/usr/bin/env bash
set -euo pipefail

CONTAINER="mindrails-qdrant"
VOLUME="mindrails-qdrant-data"
IMAGE="qdrant/qdrant:v1.17.0"
LABEL="com.mindrails.service"

fail() { echo "Error: $*" >&2; exit 1; }
exists() { docker container inspect "$CONTAINER" >/dev/null 2>&1; }
check_owner() {
    [[ "$(docker container inspect --format '{{index .Config.Labels "com.mindrails.service"}}' "$CONTAINER")" == "qdrant" ]] \
        || fail "$CONTAINER is not managed by this script."
}
start() {
    if exists; then
        check_owner
        docker start "$CONTAINER" >/dev/null
    else
        if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
            [[ "$(docker volume inspect --format '{{index .Labels "com.mindrails.service"}}' "$VOLUME")" == "qdrant" ]] \
                || fail "$VOLUME is not managed by this script."
        else
            docker volume create --label "$LABEL=qdrant" "$VOLUME" >/dev/null
        fi
        docker pull "$IMAGE"
        docker run -d --name "$CONTAINER" --label "$LABEL=qdrant" --restart unless-stopped \
            -p 127.0.0.1:6333:6333 -v "$VOLUME:/qdrant/storage" "$IMAGE" >/dev/null
    fi
    for ((attempt=0; attempt<60; attempt++)); do
        if curl --silent --fail --max-time 1 http://127.0.0.1:6333/readyz >/dev/null; then
            echo "Qdrant ready at http://127.0.0.1:6333 (volume: $VOLUME)"; return
        fi
        sleep 1
    done
    fail "Qdrant did not become ready; inspect db/qdrant.sh logs."
}
case "${1:-}" in
    start) start ;;
    stop) if exists; then check_owner; docker stop "$CONTAINER" >/dev/null; fi ;;
    restart) if exists; then check_owner; docker restart "$CONTAINER" >/dev/null; fi; start ;;
    status) if exists; then check_owner; docker inspect --format '{{.State.Status}}' "$CONTAINER"; else echo "not created"; fi ;;
    logs) exists || fail "Qdrant has not been created"; check_owner; docker logs --tail 100 "$CONTAINER" ;;
    *) echo "Usage: $0 {start|stop|restart|status|logs}"; exit 1 ;;
esac
