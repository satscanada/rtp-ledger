# .github/copilot-instructions.md — RTP Ledger

## Project Type
Java 21 / Spring Boot 3.2 multi-module Maven project.
High-throughput RTP transaction aggregation layer.
NO Kotlin, NO Gradle. Lombok is the standard for reducing Java boilerplate where appropriate.

## Non-Negotiable Rules

### Arithmetic
- ALL monetary values use `java.math.BigDecimal`
- Scale: always `setScale(2, RoundingMode.HALF_EVEN)`
- NEVER use `double`, `float`, or `int` for money

### Concurrency
- LMAX Disruptor ring buffer size MUST be a power of 2
- Default ring buffer size: `65536`
- Single producer pattern on the client; multi-producer allowed on server
- `ChronicleBalanceEngine.compute()` is the ONLY place balance is mutated
- Never call `chronicleMap.put()` directly for balance — always use `compute()`

### NATS
- Subject pattern: `ledger.{region}.{accountId}` — no other pattern
- Request timeout: 5000ms default
- Connection factory bean name: `natsConnection` (both client and server)
- NATS is pure transport — no JetStream, no KV, no object store

### Chronicle
- Chronicle Map persistence path: injected via `${chronicle.map.path}` property
- Chronicle Queue persistence path: injected via `${chronicle.queue.path}` property
- Tail pointer index class: `net.openhft.chronicle.queue.ExcerptTailer`
- Always call `tailer.toEnd()` before `tailer.index()` on startup if no stored pointer

### CockroachDB
- DSN from env var: `CRDB_URL`
- All tables hash-sharded on `account_id`
- Use `UPSERT` for `ledger_balance`, `INSERT` for `ledger_entry`
- Connection pool: HikariCP, max 20, min idle 5
- Never use `@Transactional` on the drainer — manual commit for batch control

### Spring Boot
- Use `@ConfigurationProperties` for all config — no `@Value` for complex types
- `@RestController` + `@Service` + `@Repository` layering strictly enforced
- No field injection (`@Autowired`) — constructor injection only
- Preferred constructor injection: Lombok `@RequiredArgsConstructor`
- Actuator endpoints enabled: `health`, `metrics`, `prometheus`

### Lombok
- Use Lombok to reduce non-critical boilerplate in DTOs/config/handlers
- Preferred annotations: `@RequiredArgsConstructor`, `@Getter`, `@Setter` (only where mutable), `@Builder`, `@Slf4j`
- Do not use Lombok in ways that obscure hot-path logic or financial arithmetic
- Explicitly keep money fields as `BigDecimal` with required scale handling

### Testing & Quality
- Unit tests for: `BianTransactionValidator`, `ChronicleBalanceEngine`, `QueueDrainer`
- No mocking of Chronicle Map — use a real in-memory instance in tests
- K6 tests target client HTTP only — never NATS directly

## What Copilot Should NOT Do
- Do not suggest reactive/WebFlux — this is blocking servlet with virtual threads
- Do not add Spring Data JPA — JDBC template only for performance
- Do not wrap Chronicle Map in a Spring `@Bean` unless explicitly in `ChronicleConfig`
- Do not suggest H2 for testing — use Testcontainers CockroachDB
