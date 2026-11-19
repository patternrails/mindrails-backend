#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
umask 077
mkdir -p data

if [[ ! -e .env ]]; then
  admin_password=$(openssl rand -hex 16)
  mysql_password=$(openssl rand -hex 24)
  provider_key=$(openssl rand -base64 32)
  (
    set -o noclobber
    sed -e "s|^MINDRAILS_ADMIN_PASSWORD=$|MINDRAILS_ADMIN_PASSWORD=$admin_password|" \
        -e "s|^MINDRAILS_MYSQL_PASSWORD=$|MINDRAILS_MYSQL_PASSWORD=$mysql_password|" \
        -e "s|^MINDRAILS_PROVIDER_ENCRYPTION_KEY=$|MINDRAILS_PROVIDER_ENCRYPTION_KEY=$provider_key|" \
        -e "s|^# MINDRAILS_CONTAINER_USER=1000:1000$|MINDRAILS_CONTAINER_USER=$(id -u):$(id -g)|" \
        backend.env.example > .env
  )
fi

if [[ ! -e db/.env ]]; then
  root_password=$(openssl rand -hex 24)
  (
    set -o noclobber
    printf 'MYSQL_ROOT_PASSWORD=%s\n' "$root_password" > db/.env
  )
fi
