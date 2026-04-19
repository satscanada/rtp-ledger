# PRODUCTION_TODO.md — RTP Ledger Production Hardening Backlog

Findings from code review and architectural rating conducted 2026-04-18.
Each item is a prerequisite or strong recommendation before moving from prototype to production.

---

## P0 — Data Correctness / Silent Loss Risks

### P0-01 — Server Disruptor ring-full causes silent data loss
**File**: `server/src/main/java/com/rtpledger/server/nats/NatsSubscriber.java` (line 66–70)
**Problem**: When `ledgerRingBuffer.tryPublishEvent()` returns false, the server logs a warn and discards the
message. The client already returned `202 ACCEPTED` to the caller. There is no failure reply sent back
through NATS, so the caller has no indication the transaction was dropped.
**Fix**: On ring-full, publish a NATS failure reply to `msg.getReplyTo()` before dropping, so the client
can propagate a 503 upstream. Also increment a `rtp.server.disruptor.ring.drops` metric.

### P0-02 — No NATS reconnection loop on subscriber
**File**: `server/src/main/java/com/rtpledger/server/nats/NatsSubscriber.java`
**Problem**: If the NATS connection drops, the Dispatcher closes silently. No reconnection is attempted,
and the server stops processing transactions with no health signal degradation visible to external monitors.
**Fix**: Configure the NATS `Options` with a `connectionListener` that re-subscribes on reconnect, and
expose a health indicator that fails if the NATS connection is not CONNECTED.

---

## P1 — Correctness Under Load

### P1-01 — Chronicle Map balance currency mixed across accounts
**File**: `server/src/main/java/com/rtpledger/server/chronicle/ChronicleBalanceEngine.java`
**Problem**: Each balance entry stores its own currency in the Chronicle Map JSON blob. If a transaction
arrives with a mismatched currency (e.g., CAD account receiving a USD posting), the Chronicle Map
silently overwrites the currency on the next compute. There is no cross-currency guard.
**Fix**: On `applyPosting`, assert `currency == existingCurrency` before mutating. Reject mismatches with
an `IllegalArgumentException` and reply FAILED to NATS.

### P1-02 — Negative balance allowed on DBIT postings
**File**: `server/src/main/java/com/rtpledger/server/chronicle/ChronicleBalanceEngine.java` (line 61)
**Problem**: `previous.add(signedDelta)` can produce a negative balance with no guard. For RTP debit
transactions this may be intentional (overdraft), but it is not enforced either way.
**Fix**: Add a configurable `rtp.server.allow-negative-balance` property (default `false`). If false,
reject DBIT postings that would produce a negative balance with a NATS failure reply.

---

## P2 — Scalability / Architecture

### P2-01 — Chronicle Map is single-server; horizontal scale breaks balance correctness
**File**: Architecture-wide
**Problem**: Chronicle Map lives off-heap on a single JVM. Adding a second `rtp-server` instance creates
two independent balance stores, splitting account ownership. Balance reads from either server will diverge
immediately.
**Fix**: Implement consistent hashing of `accountId` to server instances at the NATS subject routing level.
Each account is owned by exactly one server. The client must route `ledger.{region}.{accountId}` to the
correct server, either via subject-based NATS queue groups with account affinity, or via a routing tier.
Alternative: replace Chronicle Map with a distributed off-heap store (e.g., Apache Ignite, Hazelcast
off-heap) for multi-node deployments.

### P2-02 — No dead-letter queue for Chronicle Queue append failures
**File**: `server/src/main/java/com/rtpledger/server/chronicle/ChronicleQueueAppender.java`
**Problem**: If `appendPostingPayload()` throws, the `LedgerEventHandler` catches the exception, sends a
NATS FAILED reply, and the Disruptor moves on. The transaction is permanently lost — it will never reach
CockroachDB.
**Fix**: On append failure, serialize the `PostingResult` to a side-channel dead-letter file or NATS
subject (`ledger.dlq.{accountId}`) for operator review and manual replay.

### P2-03 — QueueDrainer single-threaded; no catch-up parallelism
**File**: `server/src/main/java/com/rtpledger/server/drain/QueueDrainer.java`
**Problem**: The drainer is a single background thread reading Chronicle Queue sequentially. Under sustained
high load where the queue backlog grows faster than the drain rate, the system cannot catch up without
manual operator intervention.
**Fix**: Add a configurable `rtp.server.drainer.threads` property. Run N tailer threads, each owning a
disjoint Chronicle Queue roll file (by date segment) or a named tailer position range. Coordinate tail
pointer commits per-thread.

---

## P3 — Security

### P3-01 — No mTLS on NATS connections
**File**: `infra/docker/nats.conf`, all module `application.yml` NATS config
**Problem**: All NATS connections use plain TCP with username/password only. Any process on the same
Docker network can publish to `ledger.>` subjects.
**Fix**: Enable TLS on the NATS server with client certificate verification (`verify_and_map`). Provision
per-module certs (client, server, simulator). Use NATS NKeys for cryptographic identity.

### P3-02 — No authentication or authorization on HTTP endpoints
**File**: `client/src/main/java/com/rtpledger/client/controller/LedgerPostingController.java`
**Problem**: `POST /api/v1/ledger/{region}/{accountId}/post` and the balance GET are open with no auth.
Any caller can post transactions to any account.
**Fix**: Add OAuth2 resource server (`spring-boot-starter-oauth2-resource-server`) or API key middleware.
Scope transaction posting to callers authorized for the target `accountId`.

### P3-03 — CockroachDB connection uses root user with no password in defaults
**File**: `server/src/main/resources/application.yml` (line 9–10)
**Problem**: Default `CRDB_USER=root`, `CRDB_PASSWORD=` (empty). Fine for local dev, dangerous if defaults
leak to a shared environment.
**Fix**: Remove the default value for `CRDB_PASSWORD` (force explicit set). Create a least-privilege DB
user (`rtp_server`) with INSERT/UPDATE on `ledger_entry`, `ledger_balance`, `tail_pointer` only.

---

## P4 — Observability & Operations

### P4-01 — No alerting rules defined for critical metrics
**File**: `infra/prometheus/` (no `alerts.yml` exists)
**Problem**: The Grafana dashboard displays `rtp.server.chronicle.queue.lag` with a noted threshold of
10K, but no Prometheus alerting rule fires if this is breached. Same for drainer flush failures and
balance corruption detected.
**Fix**: Add `infra/prometheus/alerts.yml` with rules for:
- `chronicle_queue_lag > 10000` for > 30s → CRITICAL
- `rtp_server_drainer_flush_failures_total` rate > 0 for > 60s → WARNING
- `rtp_server_balance_corruption_detected_total` rate > 0 → CRITICAL (page immediately)
- NATS connection down → CRITICAL

### P4-02 — No structured correlation ID propagation across the full call chain
**File**: Client controller, NATS publish handler, server event handler
**Problem**: `correlationId` is generated at the client and included in NATS payloads, but it is not
set as an MDC key or propagated as a NATS header. Log correlation across client → NATS → server →
drainer requires manually grepping logs for the UUID.
**Fix**: Set `correlationId` in SLF4J MDC at every thread boundary (client HTTP thread, Disruptor thread,
drainer thread). Use NATS message headers to carry it across the network hop.

### P4-03 — No readiness probe differentiation from liveness
**File**: `infra/docker/docker-compose.yml`, all module `application.yml`
**Problem**: Spring Actuator `/actuator/health` is used for both liveness and readiness. A server that is
alive but whose NATS subscriber is not yet connected, or whose Chronicle Map is not yet initialized, will
still return UP.
**Fix**: Implement a custom `ReadinessIndicator` that checks NATS connection state and Chronicle Map
availability. Wire it to `/actuator/health/readiness`. Split liveness (`/actuator/health/liveness`) and
readiness checks in the compose healthcheck commands.

---

## P5 — Resilience

### P5-01 — No rate limiting on client HTTP ingress
**File**: `client/src/main/java/com/rtpledger/client/controller/LedgerPostingController.java`
**Problem**: The Disruptor ring-full path returns 503, but there is no upstream rate limiter to shed load
before the ring fills. A single misconfigured caller can spike the ring and cause 503s for all other
callers.
**Fix**: Add a `resilience4j-ratelimiter` or Bucket4j rate limiter per `accountId` or per source IP at
the controller layer, before the ring buffer publish attempt.

### P5-02 — Drainer does not retry on transient CockroachDB errors
**File**: `server/src/main/java/com/rtpledger/server/drain/QueueDrainer.java` (line 181–185)
**Problem**: Any JDBC exception causes the drainer to rollback, increment `drainer.flush.failures`, and
re-attempt the same batch on the next iteration. CockroachDB 40001 (serialization failure) and transient
network errors are not distinguished from schema errors. The drainer has no backoff or retry cap.
**Fix**: Catch `PSQLException` and inspect the SQLState. For 40001 and connection errors, retry with
exponential backoff up to a configurable limit. For all other errors, move to dead-letter and advance
the tailer to prevent infinite stall.

---

## Implementation Order (suggested)

| Priority | Item | Rationale |
|----------|------|-----------|
| 1 | P0-01 — Ring-full silent drop | Data loss risk, client already accepted |
| 2 | P0-02 — NATS reconnection | Availability — silent failure with no recovery |
| 3 | P1-01 — Currency mismatch guard | Balance correctness under multi-currency load |
| 4 | P1-02 — Negative balance guard | Business rule enforcement |
| 5 | P4-01 — Prometheus alert rules | Ops visibility before any load |
| 6 | P4-02 — Correlation ID in MDC | Debug speed in production |
| 7 | P5-02 — Drainer CockroachDB retry | Resilience against transient DB failures |
| 8 | P3-01 — NATS mTLS | Security baseline |
| 9 | P3-02 — HTTP auth | Security baseline |
| 10 | P2-01 — Horizontal scale design | Architecture milestone — design before implementation |
| 11 | P2-02 — Dead-letter queue | Durability guarantee |
| 12 | P2-03 — Multi-thread drainer | Throughput ceiling |
| 13 | P3-03 — DB least-privilege user | Security hardening |
| 14 | P4-03 — Readiness probe split | Operational correctness |
| 15 | P5-01 — Rate limiting | Traffic shaping |
