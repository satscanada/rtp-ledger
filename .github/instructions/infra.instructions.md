# .github/instructions/infra.instructions.md

## Infra — Docker Compose, CockroachDB DDL, K6, Observability

### Docker Compose Stack

Services required (all in `infra/docker/docker-compose.yml`):

| Service | Image | Port | Notes |
|---------|-------|------|-------|
| `nats` | `nats:2.10-alpine` | 4222, 8222 | `--http_port 8222` for Prometheus scrape |
| `nats-surveyor` | `natsio/nats-surveyor` | 7777 | NATS GUI + Prometheus exporter |
| `crdb-1` | `cockroachdb/cockroach:v23.2.0` | 26257, 8080 | Node 1, `--join=crdb-1,crdb-2,crdb-3` |
| `crdb-2` | `cockroachdb/cockroach:v23.2.0` | 26258 | Node 2 |
| `crdb-3` | `cockroachdb/cockroach:v23.2.0` | 26259 | Node 3 |
| `crdb-init` | `cockroachdb/cockroach:v23.2.0` | — | One-shot init: `cockroach init` + DDL + seed |
| `rtp-client` | `rtp-ledger/client:latest` | 8080 | Exposes `/actuator/prometheus` |
| `rtp-server` | `rtp-ledger/server:latest` | 8081 | Exposes `/actuator/prometheus` |
| `prometheus` | `prom/prometheus:v2.51.0` | 9090 | Scrapes all services |
| `grafana` | `grafana/grafana:10.4.0` | 3000 | Pre-provisioned dashboards |
| `k6` | `grafana/k6:0.50.0` | — | Run-to-completion test runner |

Volume mounts:
- `./chronicle/map:/data/chronicle/map` (server)
- `./chronicle/queue:/data/chronicle/queue` (server)
- `./db:/docker-entrypoint-initdb.d` (crdb-init)
- `./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml` (prometheus)
- `./grafana/provisioning:/etc/grafana/provisioning` (grafana)
- `./grafana/dashboards:/var/lib/grafana/dashboards` (grafana)

### CockroachDB DDL Rules

All tables must include:
```sql
-- Hash sharding syntax for CockroachDB
USING HASH WITH (bucket_count = 8)
```

Table: `account`
```sql
CREATE TABLE account (
  account_id        UUID          NOT NULL DEFAULT gen_random_uuid(),
  account_number    VARCHAR(34)   NOT NULL,
  account_name      VARCHAR(255)  NOT NULL,
  currency          CHAR(3)       NOT NULL,
  status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
  created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id) USING HASH WITH (bucket_count = 8),
  UNIQUE INDEX uq_account_number (account_number)
);
```

Table: `ledger_entry`
```sql
CREATE TABLE ledger_entry (
  entry_id              UUID          NOT NULL DEFAULT gen_random_uuid(),
  account_id            UUID          NOT NULL,
  correlation_id        UUID          NOT NULL,
  end_to_end_id         VARCHAR(35)   NOT NULL,
  payment_info_id       VARCHAR(35)   NOT NULL,
  debit_credit_indicator VARCHAR(4)   NOT NULL CHECK (debit_credit_indicator IN ('CRDT','DBIT')),
  amount                DECIMAL(19,4) NOT NULL,
  currency              CHAR(3)       NOT NULL,
  previous_balance      DECIMAL(19,4) NOT NULL,
  current_balance       DECIMAL(19,4) NOT NULL,
  local_instrument      VARCHAR(10)   NOT NULL,
  value_date            DATE          NOT NULL,
  booking_date          DATE          NOT NULL DEFAULT current_date(),
  status                VARCHAR(10)   NOT NULL DEFAULT 'POSTED',
  chronicle_index       BIGINT,
  created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, entry_id) USING HASH WITH (bucket_count = 8),
  INDEX idx_ledger_correlation (correlation_id),
  INDEX idx_ledger_e2e (end_to_end_id),
  INDEX idx_ledger_created (account_id, created_at DESC)
);
```

Table: `ledger_balance`
```sql
CREATE TABLE ledger_balance (
  balance_id    UUID          NOT NULL DEFAULT gen_random_uuid(),
  account_id    UUID          NOT NULL,
  balance_type  VARCHAR(4)    NOT NULL DEFAULT 'CLBD',
  amount        DECIMAL(19,4) NOT NULL,
  currency      CHAR(3)       NOT NULL,
  as_of_date    DATE          NOT NULL DEFAULT current_date(),
  updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, balance_type) USING HASH WITH (bucket_count = 8)
);
```

Table: `tail_pointer`
```sql
CREATE TABLE tail_pointer (
  server_id       VARCHAR(255)  NOT NULL,
  chronicle_index BIGINT        NOT NULL,
  committed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (server_id)
);
```

### Prometheus Scrape Config (`infra/prometheus/prometheus.yml`)

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: rtp-client
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['rtp-client:8080']
        labels: { service: 'rtp-client' }

  - job_name: rtp-server
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['rtp-server:8081']
        labels: { service: 'rtp-server' }

  - job_name: nats
    metrics_path: /metrics
    static_configs:
      - targets: ['nats-surveyor:7777']
        labels: { service: 'nats' }

  - job_name: cockroachdb
    metrics_path: /_status/vars
    static_configs:
      - targets: ['crdb-1:8080']
        labels: { service: 'cockroachdb', node: 'crdb-1' }
      - targets: ['crdb-2:8080']
        labels: { service: 'cockroachdb', node: 'crdb-2' }
      - targets: ['crdb-3:8080']
        labels: { service: 'cockroachdb', node: 'crdb-3' }

  - job_name: k6
    static_configs:
      - targets: ['k6:5665']
        labels: { service: 'k6' }
```

### Spring Boot Metrics to Expose

Both client and server must expose these custom Micrometer metrics via `/actuator/prometheus`:

**Client metrics** (register in `NatsPublishEventHandler`):
- `rtp.client.transactions.accepted` — counter, tags: region, accountId-bucket
- `rtp.client.transactions.rejected` — counter, tags: reason
- `rtp.client.disruptor.publish.latency` — timer (enqueue to ring buffer)
- `rtp.client.nats.publish.latency` — timer (NATS publish from EventHandler)
- `rtp.client.nats.publish.failures` — counter
- `rtp.client.disruptor.ring.remaining` — gauge (remaining ring buffer capacity)

**Server metrics** (register in `LedgerEventHandler` and `QueueDrainer`):
- `rtp.server.balance.compute.latency` — timer (Chronicle Map compute duration)
- `rtp.server.queue.append.latency` — timer (Chronicle Queue append duration)
- `rtp.server.nats.reply.latency` — timer (NATS reply send duration)
- `rtp.server.drainer.batch.size` — histogram (entries per flush)
- `rtp.server.drainer.flush.latency` — timer (CockroachDB batch insert duration)
- `rtp.server.drainer.flush.failures` — counter
- `rtp.server.chronicle.queue.lag` — gauge (unread entries: appender index − tailer index)

### Build Baseline
- All Java services are Spring Boot 3.2 / Java 21 Maven modules
- Lombok is enabled as a standard dependency for application modules
- Keep annotation processing enabled in local IDE/build pipelines so Lombok-generated constructors and builders compile consistently

### Grafana Provisioning

Dashboard file: `infra/grafana/dashboards/rtp-ledger.json`

Must include these panels arranged in 3 rows:

**Row 1 — Throughput & Latency (K6 + Application)**
- Panel: `Transaction Rate` — `rate(rtp_client_transactions_accepted_total[1m])` — unit: TPS
- Panel: `HTTP p95 / p97 / p99` — histogram_quantile on `http_server_requests_seconds_bucket` — target lines at 10ms / 15ms / 25ms
- Panel: `NATS Publish Latency p99` — `rtp_client_nats_publish_latency` p99
- Panel: `Balance Compute Latency p99` — `rtp_server_balance_compute_latency` p99

**Row 2 — System Health**
- Panel: `Disruptor Ring Buffer Remaining` — `rtp_client_disruptor_ring_remaining` gauge
- Panel: `Chronicle Queue Lag` — `rtp_server_chronicle_queue_lag` gauge — alert threshold at 10000
- Panel: `Drainer Batch Size` — histogram heatmap of `rtp_server_drainer_batch_size`
- Panel: `Drainer Flush Latency` — p95/p99 of `rtp_server_drainer_flush_latency`

**Row 3 — NATS & CockroachDB**
- Panel: `NATS Message Rate` — from nats-surveyor metrics
- Panel: `NATS Slow Consumers` — alert if > 0
- Panel: `CockroachDB QPS` — from CockroachDB `sql_query_count` metric
- Panel: `CockroachDB p99 SQL Latency` — `sql_service_latency` p99

Datasource provisioning file: `infra/grafana/provisioning/datasources/prometheus.yml`
Dashboard provisioning file: `infra/grafana/provisioning/dashboards/rtp.yml`

### K6 Test Suite

#### File Structure
```
infra/k6/
├── rtp_load_test.js        # Main test entrypoint — all scenarios
├── rtp_concurrent_test.js  # Dedicated concurrency + contention test
├── payload_generator.js    # BIAN mock payload factory
├── accounts.js             # Seeded account ID constants (from V2 seed)
└── README.md               # How to run each test, interpret results
```

#### `rtp_load_test.js` — Scenarios

```javascript
export const options = {
  scenarios: {
    warm_up: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      tags: { scenario: 'warm_up' },
    },
    hot_account_burst: {
      executor: 'constant-vus',
      vus: 200,
      duration: '60s',
      startTime: '30s',
      // ALL 200 VUs target the SAME accountId — Apple Pay aggregation pattern
      tags: { scenario: 'hot_account_burst' },
    },
    mixed_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      startTime: '30s',
      stages: [
        { duration: '30s', target: 400 },
        { duration: '60s', target: 800 },
        { duration: '30s', target: 0 },
      ],
      // Randomized across 100 accountIds
      tags: { scenario: 'mixed_load' },
    },
  },
  thresholds: {
    // Global
    'http_req_duration': ['p(95)<10', 'p(99)<25'],
    'http_req_failed': ['rate<0.001'],
    // Per scenario
    'http_req_duration{scenario:warm_up}': ['p(99)<8'],
    'http_req_duration{scenario:hot_account_burst}': ['p(95)<15', 'p(99)<30'],
    'http_req_duration{scenario:mixed_load}': ['p(95)<12', 'p(99)<25'],
  },
};
```

#### `rtp_concurrent_test.js` — Concurrency Stress Test

Tests specifically for race conditions, balance correctness under contention,
and Disruptor backpressure behaviour. Three sub-tests:

**Test 1 — Single Account Hammer (sequential balance correctness)**
```javascript
// 500 VUs, all targeting account ID: HOT_ACCOUNT_ID
// Each VU sends 100 transactions of $1.00 CRDT
// Expected: final balance = initial + (500 * 100 * 1.00)
// Validate via GET /api/v1/ledger/{region}/{accountId}/balance after test
// Purpose: prove Chronicle Map compute() serialisation holds under 500 concurrent writers
```

**Test 2 — Burst Spike (Disruptor backpressure)**
```javascript
// Ramp: 0 → 2000 VUs in 10s, hold 10s, drop to 0 in 5s
// Purpose: trigger ring buffer pressure, measure 503 OVERLOADED rate
// Acceptable: up to 5% 503s — zero 500s
// Threshold: 'http_req_duration{status:202}': p(99) < 50ms even under spike
```

**Test 3 — Parallel Account Lanes (no cross-account interference)**
```javascript
// 10 groups of 50 VUs, each group owns 1 exclusive accountId
// All groups run concurrently for 60s
// Purpose: prove per-account Chronicle Map compute() doesn't block across accounts
// Success: p99 latency for each group is within 5ms of single-account baseline
```

#### `payload_generator.js` — BIAN Mock Factory

```javascript
export function generateBianPayload(debtorAccountId, creditorAccountId, indicator) {
  return {
    endToEndId: uuidv4(),
    paymentInformationId: uuidv4(),
    localInstrument: 'RTP',
    instructedAmount: {
      amount: randomAmount(1.00, 9999.99),  // 2dp, BigDecimal-safe string
      currency: 'CAD',
    },
    debtorAccount: { accountId: debtorAccountId, accountType: 'CACC' },
    creditorAccount: { accountId: creditorAccountId, accountType: 'CACC' },
    debitCreditIndicator: indicator,  // 'CRDT' or 'DBIT'
    remittanceInformation: 'RTP payment via K6 load test',
    valueDate: new Date().toISOString().split('T')[0],
  };
}
// amounts always formatted as strings with exactly 2dp: "1234.56"
// never send as float — CockroachDB DECIMAL precision matters
```

#### K6 Output — Prometheus Remote Write

```javascript
// Enable in docker-compose k6 command:
// k6 run --out experimental-prometheus-rw \
//        --env K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write \
//        /scripts/rtp_load_test.js
```

Key K6 metrics visible in Grafana:
- `k6_http_req_duration` — full percentile breakdown (p50/p90/p95/p97/p99)
- `k6_http_reqs` — total request rate
- `k6_http_req_failed` — error rate
- `k6_vus` — live VU count (concurrency curve)
- `k6_iterations` — completed iterations
- `k6_data_sent` / `k6_data_received` — throughput

#### `README.md` — How to Run

Must include these sections:
1. **Prerequisites** — Docker Desktop 4.x, 8GB RAM minimum for full stack
2. **Start the stack** — `docker compose up -d` order, health check commands
3. **Seed verification** — confirm 100 accounts exist before running tests
4. **Run load test** — `docker compose run k6 run /scripts/rtp_load_test.js`
5. **Run concurrency test** — `docker compose run k6 run /scripts/rtp_concurrent_test.js`
6. **View results** — Grafana at `http://localhost:3000` (admin/admin), import dashboard
7. **Interpreting p95/p97/p99** — what each percentile means for RTP SLA
8. **Expected baseline numbers** — warm-up p99 < 8ms, hot burst p99 < 30ms
9. **Failure triage** — what Chronicle Queue lag > 10K means, how to read Disruptor metrics
10. **Clean up** — `docker compose down -v` to reset Chronicle volumes

### NATS Surveyor (GUI)
- Accessible at `http://localhost:7777`
- Configure with `--servers nats://nats:4222`
- Shows: connection count, message rate, subject activity, slow consumers
- Also exposes `/metrics` for Prometheus scrape (included in scrape config above)

### CockroachDB Admin UI
- Node 1 admin UI: `http://localhost:8080`
- Shows: SQL QPS, p99 SQL latency, range distribution, hot ranges
- Verify hash sharding is working: no single range should have > 30% of QPS

