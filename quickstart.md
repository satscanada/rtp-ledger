# RTP Ledger Quickstart

End-developer runbook to validate the full RTP Ledger stack, including API posting, simulator flows, k6 load/concurrency suites, and observability (Prometheus/Grafana).

## 1) Prerequisites

- Docker Desktop with Compose v2
- At least 8 GB RAM allocated to Docker
- `curl` installed
- Optional: Postman for manual API testing

## 2) Start the full stack

From repo root:

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

## 3) Validate service health

```bash
curl -fsS http://localhost:18080/actuator/health
curl -fsS http://localhost:8081/actuator/health
curl -fsS http://localhost:8082/actuator/health
curl -fsS http://localhost:8222/healthz
```

Expected: all return healthy responses.

## 4) Run baseline smoke test

```bash
./scripts/smoke-test.sh
```

Expected:
- five POST requests are accepted (`202`)
- balance endpoint returns a non-zero balance

## 5) Test posting API directly (curl or Postman)

### POST transaction

```bash
curl -i -X POST "http://localhost:18080/api/v1/ledger/ca-east/b7592f32-d833-52f5-83c4-1c2f367e52ab/post" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "manual-msg-1",
    "creationDateTime": "2026-04-20T12:00:00Z",
    "numberOfTransactions": "1",
    "totalInterbankSettlementAmount": "10.50",
    "interbankSettlementCurrency": "CAD",
    "paymentInformationId": "manual-pay-1",
    "paymentMethod": "TRF",
    "instructionPriority": "NORM",
    "requestedExecutionDate": "2026-04-20",
    "debtor": null,
    "debtorAccount": {
      "iban": null,
      "other": "0a99d33f-2eef-5aa8-a2e1-0eae24f67164",
      "currency": "CAD"
    },
    "debtorAgent": null,
    "creditor": null,
    "creditorAccount": {
      "iban": null,
      "other": "b7592f32-d833-52f5-83c4-1c2f367e52ab",
      "currency": "CAD"
    },
    "creditorAgent": null,
    "creditTransferTransactionInformation": {
      "instructionId": "manual-instr-1",
      "endToEndId": "manual-e2e-1",
      "transactionId": "manual-txn-1",
      "paymentTypeInformation": "RTP",
      "instructedAmount": "10.50",
      "instructedCurrency": "CAD",
      "chargeBearer": "DEBT",
      "remittanceInformation": {
        "unstructured": "manual test",
        "reference": "MANUAL"
      },
      "valueDate": "2026-04-20",
      "localInstrument": "RTP"
    }
  }'
```

Expected: HTTP `202` and a response containing `correlationId` and `status`.

### GET live balance

```bash
curl -fsS "http://localhost:18080/api/v1/ledger/ca-east/b7592f32-d833-52f5-83c4-1c2f367e52ab/balance"
```

Expected: JSON with `accountId`, `balance`, `currency`, `asOf`.

## 6) Run simulator scenarios

```bash
curl -fsS -X POST http://localhost:8082/simulate/apple-pay-burst
curl -fsS -X POST http://localhost:8082/simulate/google-pay-mixed
curl -fsS -X POST http://localhost:8082/simulate/single-account-drain
curl -fsS http://localhost:8082/simulate/status
```

Use simulator when you want named RTP traffic patterns without crafting load scripts manually.

## 7) Run k6 load and concurrency tests

### Main load suite

```bash
docker compose -f infra/docker/docker-compose.yml --profile k6 run --rm k6 /k6/scripts/rtp_load_test.js
```

### Concurrency suites

```bash
docker compose -f infra/docker/docker-compose.yml --profile k6 run --rm -e CONCURRENT_SCENARIO=balance k6 /k6/scripts/rtp_concurrent_test.js
docker compose -f infra/docker/docker-compose.yml --profile k6 run --rm -e CONCURRENT_SCENARIO=burst k6 /k6/scripts/rtp_concurrent_test.js
docker compose -f infra/docker/docker-compose.yml --profile k6 run --rm -e CONCURRENT_SCENARIO=parallel k6 /k6/scripts/rtp_concurrent_test.js
```

Alternative helper:

```bash
./infra/k6/execute.sh load
./infra/k6/execute.sh balance
./infra/k6/execute.sh burst
./infra/k6/execute.sh parallel
```

## 8) Verify observability (Prometheus, VictoriaMetrics, Grafana)

### Prometheus targets and UI

- UI: <http://localhost:9091>
- Ensure targets are `UP` for `rtp-client`, `rtp-server`, `rtp-simulator`, `nats`, and `cockroachdb`.

### VictoriaMetrics UI

- UI: <http://localhost:8428/vmui>
- k6 remote-write metrics should appear during/after runs.

### Grafana dashboards

- UI: <http://localhost:3000> (admin/admin)
- Open RTP dashboard and verify:
  - transaction throughput
  - latency percentiles (p95/p99)
  - ring buffer remaining capacity
  - Chronicle queue lag
  - drainer flush latency/failures

## 9) Quick failure triage

- `503 OVERLOADED` on POST: ring pressure; check ring-remaining and reduce load
- queue lag growing: drainer or DB is behind; check drainer latency/failures and Cockroach health
- no metrics in Grafana: verify Prometheus targets are `UP` and datasource is reachable
- k6 missing: run with `--profile k6`

## 10) Clean reset

```bash
docker compose -f infra/docker/docker-compose.yml down -v
```

This wipes stack state including Chronicle volumes and observability storage.
