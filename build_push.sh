#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tag=${2:-latest}

publish() {
  local name=$1 context=$2
  docker buildx build --platform linux/amd64,linux/arm64 \
    --push --tag "haarp/mindrails-$name:$tag" "$context"
}

case ${1:-all} in
  backend) publish backend "$repo_dir" ;;
  frontend) publish frontend "$repo_dir/../mindrails-frontend" ;;
  all)
    publish backend "$repo_dir"
    publish frontend "$repo_dir/../mindrails-frontend"
    ;;
  *) printf 'Usage: %s [all|backend|frontend] [tag]\n' "$0" >&2; exit 2 ;;
esac
