#!/usr/bin/env bash
# Run k6 scenarios via the Compose `k6` profile (dual remote-write is in `run.sh`).
# Usage: from repo root — ./infra/k6/execute.sh <command>
#        or            — ./execute.sh <command>  (from infra/k6)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE=(docker compose -f "${ROOT}/infra/docker/docker-compose.yml" --profile k6)

dc_run() {
  "${COMPOSE[@]}" run --rm "$@"
}

usage() {
  cat <<'EOF'
Usage: execute.sh <command>

  load            Main load test: rtp_load_test.js (warm_up, hot_account_burst, mixed_load)
  balance         Concurrency: balance correctness (500 VUs × 50k × $1.00)
  burst           Concurrency: burst spike (0→2000 VUs)
  parallel        Concurrency: 10 parallel account lanes
  all-concurrent  Runs balance, then burst, then parallel (very long)

  help            Show this message

Requires: stack up (`docker compose -f infra/docker/docker-compose.yml up -d`)

Examples:
  ./infra/k6/execute.sh load
  ./infra/k6/execute.sh balance
EOF
}

case "${1:-}" in
  load)
    dc_run k6 /k6/scripts/rtp_load_test.js
    ;;
  balance)
    dc_run -e CONCURRENT_SCENARIO=balance k6 /k6/scripts/rtp_concurrent_test.js
    ;;
  burst)
    dc_run -e CONCURRENT_SCENARIO=burst k6 /k6/scripts/rtp_concurrent_test.js
    ;;
  parallel)
    dc_run -e CONCURRENT_SCENARIO=parallel k6 /k6/scripts/rtp_concurrent_test.js
    ;;
  all-concurrent)
    echo "execute.sh: running balance..."
    dc_run -e CONCURRENT_SCENARIO=balance k6 /k6/scripts/rtp_concurrent_test.js
    echo "execute.sh: running burst..."
    dc_run -e CONCURRENT_SCENARIO=burst k6 /k6/scripts/rtp_concurrent_test.js
    echo "execute.sh: running parallel..."
    dc_run -e CONCURRENT_SCENARIO=parallel k6 /k6/scripts/rtp_concurrent_test.js
    echo "execute.sh: all-concurrent finished."
    ;;
  help|-h|--help|"")
    usage
    [[ -n "${1:-}" ]] || exit 1
    exit 0
    ;;
  *)
    echo "execute.sh: unknown command: $1" >&2
    usage >&2
    exit 1
    ;;
esac
