#!/usr/bin/env bash
# Build Spring Boot JARs with local Maven, then build rtp-* Docker images (no maven image pull).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
echo "build-local: mvn package (client, server, simulator) ..."
mvn -B -pl client,server,simulator -am package -DskipTests
cd "$ROOT/infra/docker"
echo "build-local: docker compose build ..."
docker compose build "$@"
