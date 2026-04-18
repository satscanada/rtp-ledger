# TODO.md — RTP Ledger Checkpoint Tracker

## Current Checkpoint
**CP-07: RTP Simulator Module**
Status: 🔲 NOT STARTED

---

## Project Direction Note
This plan is for a Spring Boot 3.2 / Java 21 multi-module implementation with Lombok support.
All checkpoint work should preserve architecture constraints while using Lombok to reduce boilerplate.

---

## Checkpoint Registry

### CP-01 — Project Scaffold & Shared Models
**Goal**: Maven multi-module parent POM, shared module with all BIAN/domain models, Lombok-enabled coding baseline
**Deliverables**:
- [x] `pom.xml` (Spring Boot parent + multi-module: shared, client, server, simulator)
- [x] Parent/module POMs include Lombok dependency and annotation processing configuration
- [x] `shared/pom.xml` includes Spring Boot dependency baseline for shared models
- [x] `BianCreditTransferTransaction.java` (full BIAN-inspired payload)
- [x] `LedgerEntry.java`
- [x] `LedgerBalance.java`
- [x] `Account.java`
- [x] `LedgerPostingResponse.java` (correlationId + status)
- [x] `BalanceResponse.java` (accountId + balance + currency + asOf)
- [x] `NatsReply.java` (correlationId + ledgerEntryId + currentBalance)
- [x] Shared model style aligned with Lombok-first conventions from `CLAUDE.md` and `.github/copilot-instructions.md`

**STOP GATE**: All shared models compile cleanly with `mvn compile` → PROCEED to CP-02 ✅

---

## Cross-Checkpoint Coding Baseline
- Spring Boot 3.2 + Java 21 + Maven multi-module remains the project standard
- Lombok is approved across modules for boilerplate reduction; use constructor injection (`@RequiredArgsConstructor`) by default
- Keep architecture constraints unchanged (Disruptor/NATS/Chronicle/CockroachDB) when applying Lombok
- Keep all financial arithmetic and balance mutation rules unchanged (`BigDecimal`, `ChronicleBalanceEngine.compute()`)

---

### CP-02 — Client Module (HTTP → Disruptor → NATS)
**Goal**: Working Spring Boot client — accepts BIAN payload, returns correlation ID, exposes live balance read
**Deliverables**:
- [x] `client/pom.xml`
- [x] `LedgerPostingController.java`
  - `POST /api/v1/ledger/{region}/{accountId}/post` → `{correlationId, status: ACCEPTED}`
  - `GET  /api/v1/ledger/{region}/{accountId}/balance` → live balance from Chronicle Map via NATS request to server (NOT CockroachDB)
- [x] `BianTransactionValidator.java` (all 8 validation rules from client.instructions.md)
- [x] `TransactionDisruptorConfig.java` (ring buffer 65536, single producer)
- [x] `TransactionEvent.java` (Disruptor event wrapper)
- [x] `NatsPublishEventHandler.java` (publishes to `ledger.{region}.{accountId}`)
- [x] `BalanceQueryHandler.java` (NATS request to `ledger.balance.{region}.{accountId}`, 500ms timeout)
- [x] `ClientNatsConfig.java`
- [x] `application.yml` (client)

**Balance endpoint design note**: The GET balance makes a synchronous NATS request to the server on subject
`ledger.balance.{region}.{accountId}`. Server reads Chronicle Map and replies immediately.
This is a query path — not on the Disruptor hot path — so blocking is acceptable here.

**STOP GATE**: POST returns `{correlationId, status: ACCEPTED}`, GET balance returns live value updating during K6 run → PROCEED to CP-03

---

### CP-03 — Server Module (NATS → Disruptor → Chronicle → Reply)
**Goal**: Server receives NATS message, computes balance, appends to queue, replies. Also handles balance queries.
**Deliverables**:
- [x] `server/pom.xml`
- [x] `NatsSubscriber.java`
  - Subscribes `ledger.>` — routes 3-token subjects to Disruptor (transaction path); 4-token `ledger.balance.*.*` inline (query path, not on Disruptor)
- [x] `ServerDisruptorConfig.java`
- [x] `LedgerEventHandler.java` (Chronicle Map compute + Chronicle Queue append + NATS reply)
- [x] `ChronicleBalanceEngine.java` (off-heap balance compute + balance read method)
- [x] `ChronicleQueueAppender.java` (append LedgerEntry to queue)
- [x] `ServerNatsConfig.java`
- [x] `application.yml` (server)

**STOP GATE**: Server replies to NATS with ledgerEntryId + currentBalance when `replyTo` is set; balance query returns Chronicle Map value (full stack + NATS required) → PROCEED to CP-04

---

### CP-04 — Queue Drainer (Chronicle Queue → CockroachDB)
**Goal**: Background drainer with hybrid flush + tail pointer recovery
**Deliverables**:
- [x] `QueueDrainer.java` (hybrid: 500 records OR 50ms)
- [x] `TailPointerRepository.java` (read/write chronicle_index)
- [x] `LedgerEntryRepository.java` (JDBC batch insert)
- [x] `LedgerBalanceRepository.java` (upsert)
- [x] Recovery logic on `@PostConstruct` (seek to last committed tail pointer on restart)

**STOP GATE**: Drainer bulk-inserts to CockroachDB. Kill server, restart → tail pointer recovery replays correctly → PROCEED to CP-05 (requires schema `infra/db/V1__init.sql` + `CRDB_URL`)

---

### CP-05 — Infra (Docker Compose + DB DDL)
**Goal**: Full local stack runnable with `docker compose up -d`
**Deliverables**:
- [x] `infra/docker/docker-compose.yml` — NATS (+ vendored `nats.conf`), NATS UI (`README-NATS-UI.md`, upstream curl recipe),
      nats-surveyor, single-node CockroachDB, crdb-init, rtp-client (host **18080** → 8080), rtp-server (8081),
      rtp-simulator (8082), Prometheus (host **9091** → container 9090), Grafana (3000), Cockroach admin UI (host **28080**); K6 remains under `infra/k6/` (HTTP load, not a compose service)
- [x] `infra/db/V1__init.sql` — tables with hash-sharded PKs + indexes
- [x] `infra/db/V2__seed.sql` — 100 seed accounts (10 regions × 10), stable UUIDs for `infra/k6/accounts.js`
- [x] `infra/crdb-init/init.sh` — wait-for-SQL + apply DDL + seed
- [x] Chronicle Map/Queue named volumes on rtp-server
- [x] `infra/prometheus/prometheus.yml` — scrape client, server, simulator, nats-surveyor, single CockroachDB `/_status/vars`
- [x] `scripts/smoke-test.sh` — 5× POST → 202, balance GET → non-zero; 30s client health wait + balance poll

**STOP GATE**: `docker compose up -d` → stack ready. `./scripts/smoke-test.sh` exits 0 → PROCEED to CP-06 ✅

---

### CP-06 — Observability (Prometheus + Grafana)
**Goal**: Live dashboards showing p95/p97/p99 latency, throughput, system health — readable during a demo
**Deliverables**:
- [x] Micrometer custom metrics — client (`ClientMetrics`, `LedgerPostingController`, `NatsPublishEventHandler`; balance timer on GET path):
  - `rtp.client.transactions.accepted` (counter, tags: region)
  - `rtp.client.transactions.rejected` (counter, tag: reason)
  - `rtp.client.disruptor.publish.latency` (timer)
  - `rtp.client.nats.publish.latency` (timer)
  - `rtp.client.nats.publish.failures` (counter)
  - `rtp.client.disruptor.ring.remaining` (gauge)
  - `rtp.client.balance.query.latency` (timer — GET balance NATS roundtrip)
- [x] Micrometer custom metrics — server (`ServerMetrics`, `LedgerEventHandler`, `QueueDrainer`):
  - `rtp.server.balance.compute.latency` (timer)
  - `rtp.server.queue.append.latency` (timer)
  - `rtp.server.nats.reply.latency` (timer)
  - `rtp.server.drainer.batch.size` (distribution summary → Prometheus sum/count)
  - `rtp.server.drainer.flush.latency` (timer)
  - `rtp.server.drainer.flush.failures` (counter)
  - `rtp.server.chronicle.queue.lag` (gauge — demo alert if > 10K)
- [x] `infra/grafana/provisioning/datasources/prometheus.yml` (uid `prometheus`)
- [x] `infra/grafana/provisioning/dashboards/rtp.yml`
- [x] `infra/grafana/dashboards/rtp-ledger.json` — 3-row dashboard (TPS, HTTP/NATS/balance latency, ring & Chronicle lag, drainer, surveyor & CRDB); **5s refresh**

**STOP GATE**: Grafana at localhost:3000 shows live data from client, server, simulator scrapes with 5s refresh → PROCEED to CP-07 ✅

---

### CP-07 — RTP Simulator Module
**Goal**: Standalone Spring Boot service that mimics Apple Pay / Google Pay RTP burst patterns.
Provides a richer demo story than raw K6 — shows named payment scenarios with realistic timing.
**Deliverables**:
- [ ] `simulator/pom.xml`
- [ ] `SimulatorController.java`
  - `POST /simulate/apple-pay-burst` — fires 500 micro-transactions on HOT_ACCOUNT in 2s, then 3s lull, repeat × 3
  - `POST /simulate/google-pay-mixed` — 200 VUs across 10 accounts, randomised amounts, 60s run
  - `POST /simulate/single-account-drain` — 1000 transactions on one account, sequential, verify final balance
  - `GET  /simulate/status` — returns active scenario name, TPS, elapsed time, success/failure counts
- [ ] `RtpScenarioRunner.java` — virtual-thread executor (`Executors.newVirtualThreadPerTaskExecutor()`)
  - Each scenario runs on a dedicated virtual thread pool
  - Reports progress via SSE endpoint: `GET /simulate/events` (for Grafana annotations or terminal watch)
- [ ] `SimulatorNatsConfig.java` — reuses same NATS connection pattern as client
- [ ] `application.yml` (simulator, port 8082)

**Why virtual threads here**: The simulator is not on the hot path — it IS the load generator.
Virtual threads let us fire 500+ concurrent HTTP calls without tuning thread pool sizes.
This is the one place in the project where virtual threads are appropriate.

**STOP GATE**: `POST /simulate/apple-pay-burst` runs, Grafana shows TPS spike, balance updates visible on GET balance → PROCEED to CP-08

---

### CP-08 — K6 Performance & Concurrency Tests
**Goal**: Scripted load tests with Prometheus remote-write, all results visible in Grafana
**Deliverables**:
- [ ] `k6/payload_generator.js` — BIAN mock factory (UUID, random amount as 2dp string, always RTP/CAD)
- [ ] `k6/accounts.js` — fixed UUIDs matching V2__seed.sql (used by both K6 and simulator)
- [ ] `k6/rtp_load_test.js` — 3 scenarios with per-scenario thresholds:
  - `warm_up`: 10 VUs / 30s / single account → p99 < 8ms
  - `hot_account_burst`: 200 VUs / 60s / SAME accountId → p95 < 15ms, p99 < 30ms
  - `mixed_load`: ramp 0→400→800→0 / 120s / 100 accounts → p95 < 12ms, p99 < 25ms
- [ ] `k6/rtp_concurrent_test.js` — 3 concurrency correctness tests:
  - **Balance correctness**: 500 VUs × 100 × $1.00 CRDT → verify final balance = initial + $50,000.00
  - **Burst spike**: 0→2000 VUs in 10s → 503 rate < 5%, zero 500s
  - **Parallel lanes**: 10 groups × 50 VUs × exclusive accountId → each group p99 within 5ms of baseline
- [ ] K6 `--out experimental-prometheus-rw` pointing to Prometheus pushgateway
- [ ] `k6/README.md` — 10-section guide:
  1. Prerequisites (Docker Desktop 4.x, 8GB RAM)
  2. Start the stack (`docker compose up -d`, health check commands)
  3. Verify seed (`curl localhost:18080/api/v1/ledger/ca-east/{id}/balance` → 200)
  4. Run smoke test (`./scripts/smoke-test.sh`)
  5. Run load test (`docker compose run k6 run /scripts/rtp_load_test.js`)
  6. Run concurrency test (`docker compose run k6 run /scripts/rtp_concurrent_test.js`)
  7. Run simulator (`curl -X POST localhost:8082/simulate/apple-pay-burst`)
  8. View results in Grafana (localhost:3000, admin/admin, RTP Ledger dashboard)
  9. Interpreting p95/p97/p99 for RTP SLA context
  10. Failure triage (Chronicle lag > 10K, 503 backpressure, NATS slow consumers)

**STOP GATE**: K6 load test completes with all thresholds passing. Results visible in Grafana alongside simulator run → PROCEED to CP-09

---

### CP-09 — Pitch Assets (README + Architecture Diagram + PITCH.md)
**Goal**: The repo tells its own story when a senior opens it cold. These files ARE the pitch.
**Deliverables**:
- [ ] `README.md` (root) — sections:
  - **Problem** — RTP aggregation onto single Apple Pay / Google Pay accounts: the hot account problem
  - **Solution** — Architecture overview in 4 bullet points
  - **Architecture Diagram** — Mermaid flowchart (full flow with latency annotations at each hop)
  - **Technology Rationale** — one-line justification per technology choice
  - **Quick Start** — `docker compose up -d` → `./scripts/smoke-test.sh` → open Grafana
  - **Running Tests** — K6 and simulator commands
  - **Key Numbers** — expected p99 per scenario, Chronicle Map compute target < 50µs
- [ ] `PITCH.md` — the three questions seniors always ask:
  - **Why not Kafka?** — NATS latency profile, no broker persistence needed, Chronicle Queue owns durability
  - **Why not Redis for balance?** — Chronicle Map: off-heap, zero network hop, compute-locked per account (<1µs vs Redis ~100µs RTT)
  - **Why not Postgres / plain JDBC?** — CockroachDB hash sharding for hot ranges, geo-aware routing, no manual partitioning
  - **Why LMAX Disruptor?** — mechanical sympathy, cache-line padding, wait strategy tuning, 25M+ events/sec on commodity hardware
  - **What would production add?** — JetStream for NATS durability, mTLS, rate limiting, dead-letter handling, multi-region CockroachDB
- [ ] `ARCHITECTURE.md` — deeper technical narrative:
  - Threading model diagram (ASCII, showing which thread owns each operation)
  - Chronicle Map vs Chronicle Queue — what each owns and why
  - Balance correctness guarantee under concurrent load
  - Recovery scenario walkthrough (crash mid-batch → restart → tail pointer replay)
  - CockroachDB hot range prevention — why hash sharding matters for this use case

**STOP GATE**: A senior with no prior context can open README.md, run `docker compose up -d`,
and watch live transactions in Grafana within 5 minutes → PROTOTYPE COMPLETE ✅

---

## Completed Checkpoints
- **CP-01** — Project scaffold, shared models, Spring Boot + Lombok POM baseline (compile verified)
- **CP-02** — Spring Boot client: HTTP → Disruptor → NATS publish; balance via NATS request (`mvn compile` verified; STOP GATE with K6 when infra is up)
- **CP-03** — Spring Boot server: NATS `ledger.>` routing, Disruptor → Chronicle Map + Chronicle Queue, inline balance reads (`mvn compile` verified)
- **CP-04** — JDBC drainer thread: hybrid batch flush + tail pointer + `infra/db/V1__init.sql` reference DDL (`mvn compile` verified)
