# RTP Ledger — architecture deep dive

Technical narrative for reviewers who care about **thread ownership**, **correctness**, and **recovery**. For project rules and locks, see [`CLAUDE.md`](CLAUDE.md).

---

## Threading model

Rough ownership (one logical pipeline per deployment; actual thread names depend on Spring / LMAX / NATS):

```
HTTP worker (tomcat)
    │  POST /post  → publish to Disruptor (client)
    ▼
Disruptor publisher thread  →  ring buffer
    ▼
Disruptor event handler     →  NATS publish (fire-and-forget)
    │
    │  NATS
    ▼
NATS client dispatch thread  →  subscription callback
    │  transaction subject  →  publish to Disruptor (server)
    │  balance subject      →  INLINE Chronicle Map read + NATS reply (NOT Disruptor)
    ▼
Disruptor event handler (server)  →  Chronicle Map compute()
                                   →  Chronicle Queue append
                                   →  NATS reply (when replyTo present)
    │
    ▼
Queue drainer thread (background)   →  JDBC batch to CockroachDB
                                   →  tail pointer commit AFTER successful batch
```

**Where is the lock?** Chronicle Map’s **`compute(key, …)`** provides atomic read–modify–write **per account key**. Combined with the server’s **single consumer** handling for a given pipeline ordering (Disruptor handler calling `compute` for each event in sequence), you get **serialised balance updates per account** without application-level compare-and-swap loops.

**Balance query path** intentionally bypasses the Disruptor: the NATS subscriber handles `ledger.balance.{region}.{accountId}` **inline** with a Chronicle Map read and immediate reply—acceptable blocking for a read path that must not contend with the transaction ring buffer.

### Flow visualization path (metadata-only)

To visualize transaction movement without exposing full payloads, the system emits metadata events on `rtp.trace.v1`.

- **Client emits**: `CLIENT_HTTP_ACCEPTED|REJECTED`, `CLIENT_RING_PUBLISH_OK|REJECTED`, `CLIENT_NATS_PUBLISH_OK|FAILED`
- **Server emits**: `SERVER_NATS_RECEIVED`, `SERVER_RING_ENQUEUED|REJECTED`, `SERVER_BALANCE_COMPUTE_OK|FAILED`, `SERVER_QUEUE_APPEND_OK|FAILED`, `SERVER_NATS_REPLY_OK|FAILED`
- **Drainer emits**: `DRAINER_BATCH_FLUSH_OK|FAILED`

The client has a trace subscriber + bounded in-memory store and exposes:

- `GET /api/v1/ledger/trace/{correlationId}` — timeline for one transaction
- `GET /api/v1/ledger/trace/recent` — most recent correlations and latest stage

The subject `rtp.trace.v1` is intentionally outside the `ledger.>` wildcard, so the server subscriber never receives trace messages and no filtering is required.

---

## Chronicle Map vs Chronicle Queue

| Store | Owns | Why |
|-------|------|-----|
| **Chronicle Map** | **Working balance** per `accountId` | Off-heap, in-process, microsecond-scale `compute()` for the hot path. |
| **Chronicle Queue** | **Ordered durable log** of `LedgerEntry` records | Survives process crash; replayable for the JDBC drainer; decouples burst ingest from DB batch rate. |

The database is **not** consulted on the acceptance or balance-read paths; it is **eventually consistent** with the queue at the granularity of drainer batches and successful commits.

---

## Balance correctness under concurrent load

- **Per account:** `compute(accountId, …)` serialises mutations for that key. Many writers may contend logically, but Map semantics ensure **one winner at a time** per key for the update function.  
- **Ordering:** The server’s transaction path processes NATS-delivered work through the Disruptor handler in a **single-consumer** pattern per pipeline—events are applied **one at a time** through the handler’s `compute` call chain for that stream.  
- **Cross-account:** Independent accounts do not share a Map entry; unrelated keys progress independently.  

k6’s **balance** concurrency scenario (see [`infra/k6/README.md`](infra/k6/README.md)) stress-tests **many postings to one account** and asserts the **final HTTP-visible balance** matches the expected credit total—guarding against lost updates at the application level.

---

## Recovery walkthrough (tail pointer + queue)

1. **Steady state:** Queue grows; drainer flushes batches (500 entries **or** 50 ms). After a successful JDBC batch commit, the **tail pointer** row in CockroachDB advances to the **last committed** Chronicle index.  
2. **Crash window:** Suppose the tail pointer is **1,500,000** (committed). A batch of **400** entries has been **read** by the drainer but **not committed** when the JVM exits.  
3. **Restart:** Server reads **`tail_pointer`** → **1,500,000**. The Chronicle tailer seeks to **resume after** the last committed index and **replays** uncommitted entries into a new drain cycle.  
4. **Idempotency:** Ledger writes use deterministic identifiers from the domain model; balance upserts reflect **latest state**—replay does not double-apply monetary totals when designed with commit boundaries as implemented in the server module.

Exact class names and SQL live in the server and `infra/db` DDL—this document states the **invariant**: **pointer moves only after successful batch commit**.

---

## Hot range prevention (CockroachDB)

**Problem without careful key design:** If almost all writes land in a **narrow key span** (e.g. sequential UUIDs or a single hot key), one **range** can become a bottleneck even in a distributed SQL engine.

**Mitigation in this repo:** Tables are defined with **hash-sharded primary keys** on `account_id` (see `infra/db/V1__init.sql`) so account-centric traffic **spreads across ranges** instead of concentrating inserts and updates on a single split.

```
Without hash sharding (conceptual)          With hash sharding on account_id
─────────────────────────────────          ─────────────────────────────────
  writes ──► [ R0 | R1 | R2 ]                writes ──► R0  R1  R2  R3 ...
               ▲ hot                              ▲ spread across ranges
```

This matters for **RTP aggregation**: one merchant account can still be “hot” at the **application** layer (single Map key), but the **durable shard** layout avoids a second, unnecessary database hotspot from key ordering alone.

---

## Drain latency budget (50 ms / 500 rows)

The drainer flushes on **500 records OR 50 ms**, whichever comes first. That implies an **order-of-magnitude ceiling** on sustained drain throughput into CockroachDB (roughly **10k rows/s** per drainer instance if batches always fill before the timer). Bursts above that rate **accumulate in Chronicle Queue** temporarily; during quieter periods the drainer catches up—visible on Grafana as **Chronicle queue lag**. If lag grows without bound, the bottleneck is downstream (DB, network, or flush tuning)—see [`infra/k6/README.md`](infra/k6/README.md) failure triage.
