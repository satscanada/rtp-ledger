# Windows Manual Deployment Guide (No Docker)

This guide provides a non-Docker deployment path for Windows CMD/PowerShell that mirrors the stack in `infra/docker/docker-compose.yml`.

It includes:
- a batch orchestration script: `scripts/windows-manual-stack.bat`
- local process management (`up`, `status`, `stop`, `teardown`)
- schema + seed initialization for CockroachDB
- observability startup (Prometheus, VictoriaMetrics, Grafana)
- API validation (Postman/curl) and k6 execution

## 1) What is covered from docker-compose

Implemented in manual mode by the batch script:
- NATS
- CockroachDB
- Cockroach schema init + seed (`V1__init.sql`, `V2__seed.sql`)
- Prometheus
- VictoriaMetrics
- Grafana
- `rtp-client` (Spring Boot)
- `rtp-server` (Spring Boot)
- `rtp-simulator` (Spring Boot)
- `nats-surveyor` (optional, auto-started only if executable exists)

Not auto-started by this script:
- `nats-ui` (optional tool; run manually if you want UI parity)
- `k6` as a long-running service (run `k6` CLI ad hoc like Compose profile does)

## 2) Prerequisites

- Windows 10/11
- Java 21 (`java` on `PATH`)
- Maven 3.9+ (`mvn` on `PATH`)
- PowerShell 5+ (used internally by the batch script)
- Local binaries (place under `tools/` or override env vars):
  - `tools/nats/nats-server.exe`
  - `tools/cockroach/cockroach.exe`
  - `tools/prometheus/prometheus.exe`
  - `tools/victoriametrics/victoria-metrics.exe`
  - `tools/grafana/bin/grafana-server.exe`
  - optional: `tools/nats-surveyor/nats-surveyor.exe`
  - optional: `k6.exe` on `PATH` (or local path)

## 3) Start the stack

From repo root in CMD:

```bat
scripts\windows-manual-stack.bat up
```

What `up` does:
1. Builds jars with `mvn -DskipTests package`
2. Starts dependencies and apps as background processes
3. Waits for Cockroach SQL readiness
4. Applies DB schema and seed files
5. Starts metrics stack and application modules

All runtime logs and pids are stored under `var\windows`.

## 4) Status, stop, teardown

```bat
scripts\windows-manual-stack.bat status
scripts\windows-manual-stack.bat stop
scripts\windows-manual-stack.bat teardown
```

- `status`: process and endpoint health summary
- `stop`: stops all started processes
- `teardown`: stops everything and deletes runtime data under `var\`

## 5) Health endpoints and UIs

- Client health: `http://localhost:18080/actuator/health`
- Server health: `http://localhost:8081/actuator/health`
- Simulator health: `http://localhost:8082/actuator/health`
- NATS health: `http://localhost:8222/healthz`
- Cockroach UI: `http://localhost:28080`
- Prometheus UI: `http://localhost:9091`
- VictoriaMetrics UI: `http://localhost:8428/vmui`
- Grafana UI: `http://localhost:3000` (admin/admin)

Prometheus uses `infra/prometheus/prometheus.windows.yml` in this mode.

## 6) Postman/curl API validation

### POST transaction

```bat
curl -i -X POST "http://localhost:18080/api/v1/ledger/ca-east/b7592f32-d833-52f5-83c4-1c2f367e52ab/post" ^
  -H "Content-Type: application/json" ^
  -d "{\"messageId\":\"win-msg-1\",\"creationDateTime\":\"2026-04-20T12:00:00Z\",\"numberOfTransactions\":\"1\",\"totalInterbankSettlementAmount\":\"10.50\",\"interbankSettlementCurrency\":\"CAD\",\"paymentInformationId\":\"win-pay-1\",\"paymentMethod\":\"TRF\",\"instructionPriority\":\"NORM\",\"requestedExecutionDate\":\"2026-04-20\",\"debtor\":null,\"debtorAccount\":{\"iban\":null,\"other\":\"0a99d33f-2eef-5aa8-a2e1-0eae24f67164\",\"currency\":\"CAD\"},\"debtorAgent\":null,\"creditor\":null,\"creditorAccount\":{\"iban\":null,\"other\":\"b7592f32-d833-52f5-83c4-1c2f367e52ab\",\"currency\":\"CAD\"},\"creditorAgent\":null,\"creditTransferTransactionInformation\":{\"instructionId\":\"win-instr-1\",\"endToEndId\":\"win-e2e-1\",\"transactionId\":\"win-txn-1\",\"paymentTypeInformation\":\"RTP\",\"instructedAmount\":\"10.50\",\"instructedCurrency\":\"CAD\",\"chargeBearer\":\"DEBT\",\"remittanceInformation\":{\"unstructured\":\"manual windows test\",\"reference\":\"WIN\"},\"valueDate\":\"2026-04-20\",\"localInstrument\":\"RTP\"}}"
```

Expected: `202 ACCEPTED` with `correlationId`.

### GET live balance

```bat
curl "http://localhost:18080/api/v1/ledger/ca-east/b7592f32-d833-52f5-83c4-1c2f367e52ab/balance"
```

## 7) Simulator tests

```bat
curl -X POST http://localhost:8082/simulate/apple-pay-burst
curl -X POST http://localhost:8082/simulate/google-pay-mixed
curl -X POST http://localhost:8082/simulate/single-account-drain
curl http://localhost:8082/simulate/status
```

## 8) k6 tests on Windows (no Docker)

Run directly with local `k6.exe`:

```bat
k6 run ^
  --out experimental-prometheus-rw=http://localhost:9091/api/v1/write ^
  --out experimental-prometheus-rw=http://localhost:8428/api/v1/write ^
  infra/k6/scripts/rtp_load_test.js
```

Concurrency suite examples:

```bat
set CONCURRENT_SCENARIO=balance
k6 run ^
  --out experimental-prometheus-rw=http://localhost:9091/api/v1/write ^
  --out experimental-prometheus-rw=http://localhost:8428/api/v1/write ^
  infra/k6/scripts/rtp_concurrent_test.js
```

```bat
set CONCURRENT_SCENARIO=burst
set CONCURRENT_SCENARIO=parallel
```

## 9) Overriding binary locations

If your tools are not in `tools/`, set env vars before calling the script:

```bat
set NATS_SERVER_EXE=C:\dev\nats\nats-server.exe
set COCKROACH_EXE=C:\dev\cockroach\cockroach.exe
set PROMETHEUS_EXE=C:\dev\prometheus\prometheus.exe
set VICTORIA_METRICS_EXE=C:\dev\victoria\victoria-metrics.exe
set GRAFANA_HOME=C:\dev\grafana
set GRAFANA_EXE=C:\dev\grafana\bin\grafana-server.exe
scripts\windows-manual-stack.bat up
```

## 10) Operational notes

- Chronicle files are persisted under:
  - `var\chronicle\map`
  - `var\chronicle\queue`
- Cockroach defaults to insecure single-node mode for local dev parity with Docker.
- This path is for local development/test, not production hardening.
