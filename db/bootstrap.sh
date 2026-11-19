#!/bin/bash
# The official MySQL entrypoint runs this only for a new, empty data volume.
set -euo pipefail
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root </opt/mindrails/bootstrap.sql
