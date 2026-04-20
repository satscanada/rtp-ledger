#!/usr/bin/env bash
# Master stack manager for RTP Ledger.
# Supports start, status, stop, and teardown flows from any working directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker/docker-compose.yml"

DC=(docker compose -f "${COMPOSE_FILE}")

usage() {
  cat <<'EOF'
Usage: scripts/install.sh <command>

Commands:
  up              Build and start core stack in detached mode
  up-k6           Build and start stack with optional k6 profile enabled
  status          Show compose service status and key health endpoints
  stop            Stop running services (containers remain)
  teardown        Stop services and remove containers, networks, and volumes
  logs            Tail logs for all services
  help            Show this help message

Examples:
  ./scripts/install.sh up
  ./scripts/install.sh status
  ./scripts/install.sh stop
  ./scripts/install.sh teardown
EOF
}

require_compose_file() {
  if [[ ! -f "${COMPOSE_FILE}" ]]; then
    echo "install.sh: compose file not found at ${COMPOSE_FILE}" >&2
    exit 1
  fi
}

print_health() {
  local url="$1"
  local label="$2"

  if curl -fsS "${url}" >/dev/null 2>&1; then
    echo "  [UP]   ${label} (${url})"
  else
    echo "  [DOWN] ${label} (${url})"
  fi
}

cmd_up() {
  require_compose_file
  echo "install.sh: starting core stack..."
  "${DC[@]}" up -d --build
  echo "install.sh: stack started."
}

cmd_up_k6() {
  require_compose_file
  echo "install.sh: starting stack with k6 profile..."
  "${DC[@]}" --profile k6 up -d --build
  echo "install.sh: stack (with k6 profile) started."
}

cmd_status() {
  require_compose_file
  echo "install.sh: compose service status"
  "${DC[@]}" ps
  echo
  echo "install.sh: quick endpoint health checks"
  print_health "http://localhost:18080/actuator/health" "rtp-client"
  print_health "http://localhost:8081/actuator/health" "rtp-server"
  print_health "http://localhost:8082/actuator/health" "rtp-simulator"
  print_health "http://localhost:8222/healthz" "nats"
  print_health "http://localhost:9091/-/healthy" "prometheus"
  print_health "http://localhost:3000/api/health" "grafana"
  print_health "http://localhost:28080/health?ready=1" "cockroachdb"
}

cmd_stop() {
  require_compose_file
  echo "install.sh: stopping services..."
  "${DC[@]}" stop
  echo "install.sh: services stopped."
}

cmd_teardown() {
  require_compose_file
  echo "install.sh: tearing down stack and volumes..."
  "${DC[@]}" down -v
  echo "install.sh: teardown complete."
}

cmd_logs() {
  require_compose_file
  "${DC[@]}" logs -f
}

case "${1:-}" in
  up)
    cmd_up
    ;;
  up-k6)
    cmd_up_k6
    ;;
  status)
    cmd_status
    ;;
  stop)
    cmd_stop
    ;;
  teardown)
    cmd_teardown
    ;;
  logs)
    cmd_logs
    ;;
  help|-h|--help|"")
    usage
    [[ -n "${1:-}" ]] || exit 1
    ;;
  *)
    echo "install.sh: unknown command '${1}'" >&2
    usage >&2
    exit 1
    ;;
esac
