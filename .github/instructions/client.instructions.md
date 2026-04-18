# .github/instructions/client.instructions.md

## Client Module — Spring Boot HTTP Ingress + NATS Publisher

### Responsibility Boundary
The client module is responsible for:
1. Accepting BIAN-inspired `CreditTransferTransaction` POST requests
2. Validating the payload (structural + business rules)
3. Enqueuing to LMAX Disruptor ring buffer
4. Returning `{correlationId, status: ACCEPTED, timestamp}` immediately to caller
5. Publishing to NATS from within the Disruptor EventHandler (not the HTTP thread)

The client module is NOT responsible for:
- Balance computation (server only)
- Chronicle Map or Chronicle Queue (server only)
- CockroachDB writes (server/drainer only)

### HTTP API Contract

```
POST /api/v1/ledger/{region}/{accountId}/post
Content-Type: application/json

Response 202 Accepted:
{
  "correlationId": "uuid",
  "status": "ACCEPTED",
  "accountId": "string",
  "region": "string",
  "timestamp": "ISO-8601"
}

Response 400 Bad Request:
{
  "correlationId": "uuid",
  "status": "REJECTED",
  "errors": ["field: message", ...]
}
```

### Validation Rules (BianTransactionValidator)
Must reject if any of these are violated:
- `endToEndId` is null or blank
- `instructedAmount.amount` is null, zero, or negative
- `instructedAmount.currency` is not a valid ISO 4217 3-letter code
- `debtorAccount.accountId` is null or blank
- `creditorAccount.accountId` is null or blank
- `debtorAccount.accountId` equals `creditorAccount.accountId` (self-transfer)
- `localInstrument` is null (must be provided — e.g. "RTP", "ACH")
- `paymentInformationId` is null or blank

### Disruptor Configuration
```java
// Ring buffer: 65536 slots, single producer, blocking wait strategy
// Event: TransactionEvent wraps BianCreditTransferTransaction + correlationId + region + accountId
// Handler: NatsPublishEventHandler — publishes to ledger.{region}.{accountId}
// Thread name prefix: "rtp-client-disruptor"
```

### NATS Publish Pattern
```java
// Subject: "ledger." + region + "." + accountId
// Payload: JSON-serialized NatsMessage{correlationId, transaction, region, accountId}
// This is fire-and-forget from the EventHandler — no reply subscription on client
// Use natsConnection.publish(subject, payloadBytes)
```

### Error Handling
- Validation failure: return 400 immediately, do NOT enqueue to Disruptor
- Disruptor full (RingBuffer.tryPublishEvent fails): return 503 with `status: OVERLOADED`
- NATS publish failure in EventHandler: log ERROR with correlationId, emit metric `nats.publish.failure`
- Never throw from EventHandler — catch all exceptions, log, and continue

### Lombok Usage Rules
- Use Lombok for constructor injection and DTO boilerplate (`@RequiredArgsConstructor`, `@Getter`, `@Builder`)
- Keep Disruptor hot-path handlers explicit; do not hide publish/serialization logic behind generated magic
- Avoid mutable state unless required by framework integration; prefer immutable DTO shape
- Logging should use `@Slf4j` where applicable

### Spring Boot Config (application.yml keys)
```yaml
rtp:
  client:
    disruptor:
      ring-buffer-size: 65536
      thread-name: rtp-client-disruptor
    nats:
      servers: nats://localhost:4222
      connection-timeout-ms: 5000
      subject-prefix: ledger
```
