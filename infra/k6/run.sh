#!/bin/sh
# Wrapper entrypoint — bakes in both remote-write targets so callers only pass the script path.
#   docker compose --profile k6 run --rm k6 /k6/scripts/rtp_load_test.js
#   docker compose --profile k6 run --rm k6 -e CONCURRENT_SCENARIO=burst /k6/scripts/rtp_concurrent_test.js
exec k6 run \
  --out experimental-prometheus-rw=http://prometheus:9090/api/v1/write \
  --out experimental-prometheus-rw=http://victoriametrics:8428/api/v1/write \
  "$@"
