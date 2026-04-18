# .github/prompts/loadcontext.prompt.md

## Session Bootstrap — RTP Ledger Transaction Manager

Use this prompt at the start of every Copilot Chat or AI session on this project.

---

```
I am working on the rtp-ledger project.

Architecture summary:
- Spring Boot 3.2 / Java 21 multi-module Maven project
- Client: HTTP POST → LMAX Disruptor → NATS publish → return correlationId immediately
- Server: NATS subscribe → LMAX Disruptor → Chronicle Map balance compute → Chronicle Queue append → NATS reply
- Drainer: Chronicle Queue → CockroachDB bulk insert (hybrid: 500 records OR 50ms)
- NATS subject: ledger.{region}.{accountId} — pure transport, no JetStream
- Balance SOT: Chronicle Map (off-heap) during run; CockroachDB is audit ledger only
- Recovery: Chronicle Queue tail pointer tracking — replay unacknowledged on restart
- CockroachDB hash-sharded on account_id to prevent hot ranges

Non-negotiable rules:
- BigDecimal for all money, never double
- Ring buffer size = power of 2 (65536)
- ChronicleBalanceEngine.compute() is the ONLY balance mutation point
- QueueDrainer owns ALL CockroachDB writes
- Constructor injection only (prefer Lombok `@RequiredArgsConstructor`), no WebFlux, no JPA
- Lombok is allowed for boilerplate reduction; keep hot-path logic explicit and readable

Current checkpoint: [FILL IN from TODO.md]

Please read CLAUDE.md and TODO.md before making any suggestions.
Do not propose architectural changes — implement within the locked decisions.
```

---

## Quick Reference — Key Class Locations

| Class | Module | Package |
|-------|--------|---------|
| `LedgerPostingController` | client | `com.rtp.client.controller` |
| `BianTransactionValidator` | client | `com.rtp.client.validation` |
| `NatsPublishEventHandler` | client | `com.rtp.client.nats` |
| `NatsSubscriber` | server | `com.rtp.server.subscriber` |
| `ChronicleBalanceEngine` | server | `com.rtp.server.chronicle` |
| `ChronicleQueueAppender` | server | `com.rtp.server.chronicle` |
| `QueueDrainer` | server | `com.rtp.server.drain` |
| `LedgerEntry` | shared | `com.rtp.shared.model` |
| `BianCreditTransferTransaction` | shared | `com.rtp.shared.model` |
