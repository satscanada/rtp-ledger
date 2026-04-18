#!/usr/bin/env sh
set -eu

COCKROACH_HOST="${COCKROACH_HOST:-cockroach}"
MAX_WAIT_SEC="${MAX_WAIT_SEC:-120}"
DDL_DIR="${DDL_DIR:-/ddl}"

echo "crdb-init: waiting for SQL on ${COCKROACH_HOST}:26257 ..."

end=$(( $(date +%s) + MAX_WAIT_SEC ))
while [ "$(date +%s)" -lt "$end" ]; do
  if cockroach sql --insecure --host="$COCKROACH_HOST" -e "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if ! cockroach sql --insecure --host="$COCKROACH_HOST" -e "SELECT 1" >/dev/null 2>&1; then
  echo "crdb-init: ERROR: database not reachable at host=${COCKROACH_HOST}" >&2
  exit 1
fi

echo "crdb-init: applying ${DDL_DIR}/V1__init.sql"
cockroach sql --insecure --host="$COCKROACH_HOST" < "${DDL_DIR}/V1__init.sql"

echo "crdb-init: applying ${DDL_DIR}/V2__seed.sql"
cockroach sql --insecure --host="$COCKROACH_HOST" < "${DDL_DIR}/V2__seed.sql"

echo "crdb-init: done."
