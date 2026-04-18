# .github/instructions/simulator.instructions.md

## Simulator Module — RTP Burst Pattern Generator

### Purpose
The simulator is a demo tool, not a load testing tool. Its job is to tell a story —
it generates named, realistic payment scenarios that a senior audience can relate to:
"This is what happens when 500 Apple Pay taps land on a single merchant account in 2 seconds."

K6 measures performance. The simulator makes the demo visual and concrete.

### Responsibility Boundary
The simulator:
- Generates realistic BIAN payloads and POSTs them to the CLIENT HTTP endpoint
- Uses virtual threads for concurrent request firing (not NATS directly)
- Exposes named scenario endpoints and a live status/SSE stream
- Reports per-scenario TPS, success count, failure count, elapsed time

The simulator does NOT:
- Connect to NATS directly
- Read from Chronicle Map or Chronicle Queue
- Write to CockroachDB
- Replace K6 for threshold-based performance testing

### API Contract

```
POST /simulate/apple-pay-burst
  Body: { "accountId": "uuid", "region": "ca-east", "rounds": 3 }
  Effect: Fires 500 transactions on accountId in 2s, 3s lull, repeats 'rounds' times
  Returns: { "scenarioId": "uuid", "status": "RUNNING" }

POST /simulate/google-pay-mixed
  Body: { "accountIds": ["uuid",...(10)], "region": "ca-east", "durationSeconds": 60 }
  Effect: 200 concurrent VUs across the 10 accounts, randomised CRDT/DBIT, 60s
  Returns: { "scenarioId": "uuid", "status": "RUNNING" }

POST /simulate/single-account-drain
  Body: { "accountId": "uuid", "region": "ca-east", "count": 1000, "amount": "1.00" }
  Effect: 1000 sequential CRDT transactions of $1.00, then fetches final balance
  Returns: { "scenarioId": "uuid", "status": "RUNNING" }

GET /simulate/status
  Returns: { "active": [ { "scenarioId", "name", "tps", "sent", "success", "failed", "elapsedMs" } ] }

GET /simulate/events          (Server-Sent Events)
  Streams: { "scenarioId", "event": "TICK|COMPLETE|ERROR", "tps", "sent", "success", "failed" }
  Interval: every 500ms while scenario is running
```

### Implementation Notes

```java
// Virtual thread executor — right tool for I/O-bound concurrent HTTP calls
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

// Each scenario submitted as a Callable to the executor
// Progress tracked in a ConcurrentHashMap<scenarioId, ScenarioStatus>
// SSE endpoint reads from status map every 500ms

// HTTP client: Spring's RestClient (blocking, virtual-thread-friendly)
// NOT WebClient — no reactive stack in this project
// NOT RestTemplate — deprecated

// Payload generation reuses the same BIAN model from shared module
// Amount always BigDecimal formatted as "%.2f" string in JSON
```

### Scenarios — Timing Detail

**apple-pay-burst** (the showpiece demo scenario):
```
Round 1: fire 500 virtual threads simultaneously → all POST to same accountId
         wait for all 500 to complete or timeout (5s max)
         3s lull (sleep)
Round 2: repeat
Round 3: repeat
After all rounds: GET balance and assert it equals (initial + sum of all amounts)
```

**google-pay-mixed**:
```
For durationSeconds:
  Every 100ms: fire 20 new virtual threads (200 total in-flight target)
  Each thread: pick random accountId from list, random amount $0.01-$999.99
  Random CRDT/DBIT with 70/30 split (more credits than debits — realistic)
```

**single-account-drain** (correctness demo):
```
Fire 'count' transactions sequentially (not concurrently) — one at a time
Each $1.00 CRDT
After last transaction: GET balance from client
Assert: currentBalance == previousBalance + (count × 1.00)
Report: PASS or FAIL with expected vs actual balance in status
```

### Spring Boot Config (application.yml keys)
```yaml
rtp:
  simulator:
    client-base-url: http://rtp-client:8080
    request-timeout-ms: 5000
    max-concurrent-scenarios: 3
    sse-interval-ms: 500
```

### Lombok Usage Rules
- Use Lombok for controller/service boilerplate (`@RequiredArgsConstructor`, `@Slf4j`)
- Keep scenario timing/state-machine logic explicit and testable
- Prefer immutable request/response DTO modeling with Lombok builders where it improves readability
