# RTP Ledger — pitch notes

Direct answers to questions senior technology leaders typically ask in a first meeting. This prototype is **Java 21**, **Spring Boot 3.2**, and uses **Lombok** for boilerplate reduction—it is not the architectural story.

---

## Why not Kafka?

Kafka shines at durable log aggregation and horizontal consumer scaling, but it adds broker latency (often multiple milliseconds per produce path) and operational surface area (topics, partitions, consumer groups). Here the **durable append log is Chronicle Queue** on local disk with **sub-microsecond** append semantics on the hot path, and **NATS** provides simple pub/sub routing without Kafka’s partition model. We are not trying to replay months of history from the broker—we need **fast handoff** from ingress to an in-process balance engine and an ordered durable log for async DB drain.

---

## Why not Redis for balance?

Redis is fast, but it is still a **network hop**—on the order of **~100 µs** on a good LAN and **~1 ms** cross-AZ—before you execute anything. **Chronicle Map** keeps working balances **off-heap in process**: `compute()` on a key serialises writers for that account without a remote round-trip. For a single hot account receiving massive burst traffic, Redis still serialises through a single writer path while adding RTT and serialization costs; Chronicle Map keeps the balance where the CPU already is.

---

## Why not PostgreSQL / plain JDBC on the hot path?

A conventional RDBMS can be tuned beautifully, but **serialising every micro-posting through synchronous JDBC** on the acceptance path is exactly what breaks latency under burst. This design keeps **acceptance** off the synchronous DB path: **Chronicle Map + Queue**, then a **batched drainer** to **CockroachDB**. For persistence layout, **hash-sharded primary keys** spread account-centric writes across ranges—reducing **hot range** concentration compared to naive sequential IDs. Production might still choose Postgres or another store; the pitch is **separation of concerns**: hot path vs durable ledger drain.

---

## Why LMAX Disruptor?

The Disruptor was built for financial exchange–grade throughput: **cache-line–friendly** ring buffer, explicit producer/consumer sequencing, and wait strategies you can tune. It avoids the contention patterns of classic `BlockingQueue` designs and achieves **tens of millions of events per second** per core in benchmark scenarios—far beyond what typical bounded queues deliver. Here it gives a clear **ingress → handler** handoff with predictable behaviour under load.

---

## What would production add?

- **NATS JetStream** (or equivalent) for stronger transport durability and replay semantics where required  
- **mTLS** and hardened network policies between client, server, and data stores  
- **Rate limiting / token bucket** per account at the edge or in-process guardrails  
- **Dead-letter** handling for drain failures and poison payloads  
- **Multi-region CockroachDB** with locality-aware placement for geo routing  
- **Structured audit logging** and immutable ledger evidence aligned to compliance  
- **Circuit breakers** when balance compute or drain latency breaches policy  
- **Horizontal scaling** with explicit partitioning strategy (multiple ledger shards)—not assumed in this prototype  
