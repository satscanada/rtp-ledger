# .github/instructions/server.instructions.md

## Server Module — NATS Subscriber + Balance Engine + Drainer

### Responsibility Boundary
The server module is responsible for:
1. Subscribing to NATS subjects matching `ledger.>`
2. Deserializing and validating inbound NATS message
3. Enqueuing to LMAX Disruptor
4. Computing balance delta via Chronicle Map (off-heap, compute-locked per accountId)
5. Building and appending LedgerEntry to Chronicle Queue
6. Replying to NATS request with `{correlationId, ledgerEntryId, currentBalance}`
7. Draining Chronicle Queue to CockroachDB in bulk (QueueDrainer)
8. Persisting and recovering Chronicle Queue tail pointer

### Thread Model

```
NATS I/O thread
  └─ NatsSubscriber.onMessage()
       └─ server Disruptor.publishEvent()  ← only action on NATS thread

Disruptor handler thread (single, named "rtp-server-disruptor-0")
  └─ LedgerEventHandler.onEvent()
       ├─ ChronicleBalanceEngine.compute(accountId, transaction)
       ├─ ChronicleQueueAppender.append(ledgerEntry)
       └─ msg.replyTo → natsConnection.publish(replySubject, replyBytes)

QueueDrainer thread (single @Scheduled, named "rtp-drainer-0")
  └─ poll Chronicle Queue tailer
  └─ accumulate batch (500 records OR 50ms)
  └─ JDBC batch insert ledger_entry
  └─ JDBC upsert ledger_balance
  └─ persist tail pointer index
```

### ChronicleBalanceEngine Contract

```java
// Map key: accountId (String)
// Map value: LedgerBalance (serialized with Chronicle Map custom marshaller)
// Operation: always use compute() — never get()+put()
// Balance mutation logic inside compute():
//   previousBalance = existing.amount (or BigDecimal.ZERO if absent)
//   delta = CRDT → add, DBIT → subtract
//   currentBalance = previousBalance + delta
//   return new LedgerBalance(accountId, currentBalance, now)
// Chronicle Map file: ${chronicle.map.path}/balance.dat
// Entries: 1_000_000 (pre-allocated)
// Average value size: 128 bytes
```

### ChronicleQueueAppender Contract

```java
// Queue path: ${chronicle.queue.path}/ledger
// Roll cycle: DAILY
// Append: ExcerptAppender.writeDocument(w -> w.marshallable(ledgerEntry))
// Return: appender.lastIndexAppended() → stored in LedgerEntry.chronicleIndex
```

### QueueDrainer Contract

```java
// Tailer name: "drainer-0" (named tailer for tail pointer persistence)
// On startup (@PostConstruct):
//   1. Load last committed chronicle_index from tail_pointer table (server_id = hostname)
//   2. If found: tailer.moveToIndex(lastIndex + 1)
//   3. If not found: tailer.toStart()
// Drain loop (every 10ms @Scheduled fixed rate):
//   - Accumulate up to 500 entries OR until 50ms elapsed
//   - JDBC batch insert all accumulated ledger_entry rows
//   - JDBC upsert ledger_balance for each unique accountId in batch
//   - On success: persist max(chronicleIndex) to tail_pointer table
//   - On failure: log ERROR, do NOT advance tail pointer, retry next cycle
```

### NATS Reply Contract

```java
// Reply subject: msg.getReplyTo()  ← from NATS request/reply protocol
// Reply payload:
{
  "correlationId": "uuid",
  "ledgerEntryId": "uuid",
  "currentBalance": "decimal string",
  "currency": "ISO-4217",
  "accountId": "string",
  "timestamp": "ISO-8601"
}
// Send from Disruptor handler thread AFTER Chronicle Queue append
// Timeout if replyTo is null: log WARN and skip reply (fire-and-forget fallback)
```

### Error Handling in EventHandler
- Chronicle Map compute failure: log ERROR + correlationId, reply with `status: FAILED`
- Chronicle Queue append failure: log ERROR, do NOT reply (entry not durable)
- NATS reply failure: log WARN (entry is already durable in queue), continue
- Never let an exception propagate out of `onEvent()` — Disruptor will halt

### Lombok Usage Rules
- Prefer Lombok `@RequiredArgsConstructor` for Spring-managed components
- Use `@Slf4j` for structured logging in subscriber, handler, and drainer classes
- Use Lombok to reduce POJO boilerplate, but keep balance math and queue/tailer flow explicit in code
- Do not introduce Lombok patterns that obscure Chronicle Map compute semantics or drain commit sequencing

### Spring Boot Config (application.yml keys)
```yaml
rtp:
  server:
    disruptor:
      ring-buffer-size: 65536
      thread-name: rtp-server-disruptor
    nats:
      servers: nats://localhost:4222
      subject-wildcard: "ledger.>"
      connection-timeout-ms: 5000
    chronicle:
      map-path: /data/chronicle/map
      queue-path: /data/chronicle/queue
    drainer:
      batch-size: 500
      flush-interval-ms: 50
      server-id: ${HOSTNAME:server-default}
  datasource:
    url: ${CRDB_URL}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```
