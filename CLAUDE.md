# CLAUDE.md — RTP Ledger Transaction Manager

## Project Identity
High-speed, low-latency RTP transaction aggregation layer.
Receives BIAN-inspired ledger postings from payment networks (Apple Pay, Google Pay),
aggregates onto single accounts, and drains to CockroachDB for core banking push.
This is a prototype pitch — not an MVP. Goal: demonstrate the architecture is sound
and the latency numbers are real.

## Implementation Baseline
- Java 21 / Spring Boot 3.2 / Maven multi-module
- Lombok is approved for boilerplate reduction (`@RequiredArgsConstructor`, `@Getter`, `@Builder`, `@Slf4j`)
- Prefer explicit, immutable modeling on hot path components where performance and object shape clarity matter

## Architecture Decisions (LOCKED — do not change without STOP GATE review)

### Threading Model
- **Client**: HTTP ingress → LMAX Disruptor → NATS publish (fire-and-forget from EventHandler)
- **HTTP response**: Two-phase correlation ID — caller gets `{correlationId, status: ACCEPTED}` immediately
- **Server transaction path**: NATS subscriber → LMAX Disruptor → Chronicle Map compute → Chronicle Queue append → NATS reply
- **Server query path**: NATS `ledger.balance.>` → Chronicle Map read → NATS reply inline (NOT on Disruptor)
- **Simulator**: Virtual thread executor (`newVirtualThreadPerTaskExecutor`) — only place virtual threads are used

### Balance Source of Truth
- **Hot path**: Chronicle Map (off-heap, per-account compute-locked, sub-microsecond)
- **Durable ledger**: CockroachDB (async drain only — never on transaction hot path)
- **Balance reads**: Chronicle Map via NATS query → client GET endpoint (never CockroachDB on read path)

### Drain Strategy
- Hybrid flush: **500 records OR 50ms**, whichever fires first
- Tail pointer persisted to CockroachDB on every successful batch commit
- On restart: seek Chronicle Queue tailer to last committed tail pointer index

### NATS Subjects
- **Transaction path**: `ledger.{region}.{accountId}` — request/reply
- **Balance query path**: `ledger.balance.{region}.{accountId}` — request/reply, inline on subscriber thread
- **Pure transport** — no JetStream, no KV store, no object store

### CockroachDB Sharding
- All tables hash-sharded on `account_id` — prevents hot ranges under RTP aggregation load
- Never use sequential primary keys as shard keys

## Module Map

```
rtp-ledger/
├── shared/                  # BIAN payload, LedgerEntry, LedgerBalance, Account,
│                            #   LedgerPostingResponse, BalanceResponse, NatsReply
├── client/                  # Spring Boot HTTP ingress + NATS publisher
│                            #   POST .../post → correlationId (async, 202)
│                            #   GET  .../balance → live Chronicle Map balance
├── server/                  # NATS subscriber + balance engine + drainer
│                            #   Transaction path: Disruptor → Chronicle Map → Chronicle Queue → NATS reply
│                            #   Query path: balance.> → Chronicle Map read → NATS reply (inline)
├── simulator/               # RTP burst simulator — Apple Pay / Google Pay scenarios
│                            #   Virtual thread executor, SSE progress stream
│                            #   POST /simulate/apple-pay-burst|google-pay-mixed|single-account-drain
├── scripts/
│   └── smoke-test.sh        # 5-transaction assertion, safe post-startup
├── README.md                # Problem → Solution → Diagram → Quick Start
├── PITCH.md                 # Why not Kafka/Redis/Postgres? What would production add?
├── ARCHITECTURE.md          # Threading model, recovery walkthrough, balance correctness proof
└── infra/
    ├── docker/              # docker-compose.yml (11 services)
    ├── db/                  # V1__init.sql (DDL + hash sharding), V2__seed.sql (100 accounts)
    ├── crdb-init/           # init.sh (cluster init + DDL + seed)
    ├── prometheus/          # prometheus.yml (7 scrape targets)
    ├── grafana/             # provisioning/ + dashboards/rtp-ledger.json (3-row, 5s refresh)
    └── k6/                  # rtp_load_test.js, rtp_concurrent_test.js,
                             #   payload_generator.js, accounts.js, README.md
```

## Data Models

### account
- `account_id` (UUID, hash-sharded)
- `account_number`, `account_name`, `currency`, `status`
- `created_at`, `updated_at`

### ledger_entry
- `entry_id` (UUID), `account_id` (FK, hash-sharded)
- `correlation_id`, `end_to_end_id`, `payment_info_id`
- `debit_credit_indicator` (CRDT/DBIT)
- `amount`, `currency`
- `previous_balance`, `current_balance`
- `value_date`, `booking_date`
- `local_instrument` (RTP/ACH/etc)
- `status` (PENDING/POSTED/FAILED)
- `chronicle_index` (BIGINT — Chronicle Queue position for tail pointer recovery)
- `created_at`

### ledger_balance
- `balance_id` (UUID), `account_id` (FK, hash-sharded)
- `balance_type` (CLBD/ITBD/XPCD)
- `amount`, `currency`
- `as_of_date`, `updated_at`

### tail_pointer
- `server_id` (VARCHAR PK — hostname)
- `chronicle_index` (BIGINT)
- `committed_at`

## Critical Rules for AI Assistants

1. `ChronicleBalanceEngine` uses `chronicleMap.compute(accountId, ...)` — never replace with a DB call
2. NATS transaction reply sent from Disruptor EventHandler thread — never from the subscriber thread
3. NATS balance query reply sent inline on subscriber thread — never goes through Disruptor
4. `QueueDrainer` owns ALL CockroachDB writes — no other component writes ledger_entry or ledger_balance
5. Disruptor ring buffer size must be a power of 2 (default: 65536)
6. Chronicle Map persistence path: injected via `${chronicle.map.path}` — never hardcoded
7. Chronicle Queue persistence path: injected via `${chronicle.queue.path}` — never hardcoded
8. Tail pointer updated ONLY after successful JDBC batch commit — never optimistically
9. All monetary values use `BigDecimal` — never `double` or `float`
10. K6 and simulator target client HTTP only — never NATS directly
11. Simulator uses virtual threads (`newVirtualThreadPerTaskExecutor`) — no other module uses virtual threads
12. LiteLLM / ADK patterns do NOT apply here — pure Java 21 / Spring Boot 3.2 project
13. Lombok usage must not alter architecture boundaries or hot-path behavior; it is a boilerplate tool, not a design change

## Checkpoint Protocol
This project uses AgentForge stop-gate checkpoints.
AI must halt after each checkpoint and wait for explicit `PROCEED` before continuing.

CP-01 Shared Models → CP-02 Client → CP-03 Server → CP-04 Drainer →
CP-05 Infra → CP-06 Observability → CP-07 Simulator → CP-08 K6 Tests → CP-09 Pitch Assets
