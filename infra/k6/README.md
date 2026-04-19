# K6 load and concurrency tests

```
infra/k6/
├── run.sh                  ← entrypoint wrapper (bakes in both --out URLs)
├── scripts/
│   ├── accounts.js         ← seeded account UUIDs from V2__seed.sql
│   ├── payload_generator.js
│   ├── rtp_load_test.js
│   └── rtp_concurrent_test.js
└── README.md
```

The `k6` Compose service mounts `infra/k6/` as `/k6/` inside the container. `run.sh` is the entrypoint — it injects both `--out` URLs so callers only supply the script path:

```bash
docker compose --profile k6 run --rm k6 /k6/scripts/rtp_load_test.js
```

---

## 1. Prerequisites

- Docker Desktop **4.x** (or compatible engine + Compose v2)
- At least **8 GB RAM** allocated to Docker for the full stack + high-VU scenarios (`burst` reaches 2000 VUs)
- Optional: `k6` installed locally for ad-hoc runs against `http://localhost:18080`

---

## 2. Start the stack

From the repo root:

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

Health checks:

```bash
curl -fsS http://localhost:18080/actuator/health   # rtp-client
curl -fsS http://localhost:8081/actuator/health    # rtp-server
curl -fsS http://localhost:8222/healthz            # NATS
```

**Dual remote-write targets (k6 metrics):**

| Target | URL | Grafana datasource |
|--------|-----|--------------------|
| Prometheus | `http://prometheus:9090/api/v1/write` (`--web.enable-remote-write-receiver`) | `prometheus` (default) |
| VictoriaMetrics | `http://victoriametrics:8428/api/v1/write` | `victoriametrics` |

Both URLs are baked into `run.sh` — every k6 run dual-writes automatically.

---

## 3. Verify seed data

Pick any seeded UUID from `infra/db/V2__seed.sql` (or `infra/k6/scripts/accounts.js`). Example — `ca-east` first account:

```bash
curl -fsS "http://localhost:18080/api/v1/ledger/ca-east/b7592f32-d833-52f5-83c4-1c2f367e52ab/balance"
```

Expect HTTP **200** with `balance`, `currency`, `asOf`.

---

## 4. Run smoke test

```bash
./scripts/smoke-test.sh
```

Five `POST` → 202, then polls balance until non-zero. Default target: `http://localhost:18080`.

---

## 5. Run load test

```bash
docker compose --profile k6 run --rm k6 /k6/scripts/rtp_load_test.js
```

Three scenarios (sequential, all in one run):

| Scenario | VUs | Duration | Threshold |
|----------|-----|----------|-----------|
| `warm_up` | 10 | 30s | p99 < 8ms |
| `hot_account_burst` | 200 | 60s (starts at 30s) | p95 < 15ms, p99 < 30ms |
| `mixed_load` | 0→400→800→0 | 120s (starts at 30s) | p95 < 12ms, p99 < 25ms |

---

## 6. Run concurrency tests

Select a sub-suite with `CONCURRENT_SCENARIO` (default: `balance`):

| Value | Behaviour |
|-------|-----------|
| `balance` | 500 VUs × 100 iterations × **$1.00** CRDT → teardown asserts final balance ≈ initial + **$50,000.00** |
| `burst` | Ramp **0 → 2000 VUs** in 10s, hold 10s, ramp down — thresholds: 503 rate < 5%, zero 500s |
| `parallel` | **10 lanes** × 50 VUs × 60s, each lane owns one exclusive account |

```bash
# Balance correctness
docker compose --profile k6 run --rm -e CONCURRENT_SCENARIO=balance k6 /k6/scripts/rtp_concurrent_test.js

# Burst spike / Disruptor backpressure
docker compose --profile k6 run --rm -e CONCURRENT_SCENARIO=burst k6 /k6/scripts/rtp_concurrent_test.js

# Parallel account lanes
docker compose --profile k6 run --rm -e CONCURRENT_SCENARIO=parallel k6 /k6/scripts/rtp_concurrent_test.js
```

---

## 7. Run simulator

```bash
curl -fsS -X POST http://localhost:8082/simulate/apple-pay-burst
```

Run alongside a k6 test to see both traffic sources live on the Grafana dashboard.

---

## 8. View results in Grafana

- URL: `http://localhost:3000` — login **admin** / **admin**
- **RTP Ledger** dashboard (Prometheus) — application TPS, latency, ring buffer, Chronicle lag
- **Prometheus** Explore — query `k6_http_req_duration`, `k6_vus`, `k6_http_reqs` from remote write
- **VictoriaMetrics** Explore (`http://localhost:8428/vmui` for native UI) — same k6 metrics; use Grafana datasource switcher to compare query performance between the two backends

---

## 9. Interpreting p95 / p97 / p99 (RTP SLA context)

| Percentile | Meaning |
|-----------|---------|
| **p50** | Median — typical happy-path latency |
| **p95** | "Almost everyone" — standard SLO anchor for payment acceptance |
| **p97** | Intermediate tail; useful as a mid-point between p95 and p99 |
| **p99** | Tail — reveals GC pauses, drainer flushes, NATS backpressure, and hot-account serialisation cost |

Single-account burst (`hot_account_burst`) will show higher p99 than `mixed_load` because all 200 VUs serialise on the same Chronicle Map entry. This is intentional — it demonstrates the hot-account problem.

---

## 10. Failure triage

| Symptom | What to check |
|--------|----------------|
| **`rtp.server.chronicle.queue.lag` ≫ 10K** | Drainer can't keep up — check drainer flush latency, CRDB `/\_status/vars`, server logs |
| **Many HTTP 503 `OVERLOADED`** | Disruptor ring full — `rtp.client.disruptor.ring.remaining` near zero; reduce VU count or scale out (STOP GATE) |
| **NATS slow consumers** | Check surveyor metrics / NATS `/connz`; publisher faster than subscriber |
| **Balance correctness teardown fails** | Hot account may have been pre-credited in a prior run; do a full stack reset before re-running |
| **Thresholds fail on laptop** | Docker Desktop CPU/RAM limits; thresholds reflect intent on adequate hardware, not a guarantee on every workstation |

---

### Clean reset

```bash
docker compose -f infra/docker/docker-compose.yml down -v
```

Removes all named volumes (Chronicle map/queue, Grafana, VictoriaMetrics storage). Re-run `up -d` and wait for `crdb-init` to complete before running tests.
