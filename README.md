# RTP Ledger

High-speed, low-latency **RTP (Real-Time Payments) transaction aggregation** prototype. A Spring Boot client accepts BIAN-style ledger postings over HTTP, fans out through a LMAX Disruptor to **NATS**, and a server applies **Chronicle Map** balance updates, appends to **Chronicle Queue**, and drains asynchronously to **CockroachDB**. The goal is a demonstrable stack with real latency and observability—not a full production system.

**Project memory and rules:** see [`CLAUDE.md`](CLAUDE.md), current checkpoint: [`TODO.md`](TODO.md), and session bootstrap: [`LOAD_CONTEXT.md`](LOAD_CONTEXT.md).

## What this repository contains

| Area | Description |
|------|-------------|
| **Java modules** | `shared` (models), `client` (HTTP → Disruptor → NATS), `server` (NATS → Disruptor → Chronicle → drainer), `simulator` (load scenarios over HTTP) |
| **Infra** | `infra/docker` — Compose stack (NATS, CockroachDB, client, server, simulator, Prometheus, Grafana, VictoriaMetrics, k6 profile) |
| **Data** | `infra/db` — CockroachDB DDL and 100-account seed; `CRDB_URL` for the server |
| **Load tests** | `infra/k6` — k6 scripts, dual remote-write wrapper, [`infra/k6/README.md`](infra/k6/README.md) |

## Prerequisites

- **JDK 21** and **Maven 3.9+** for local builds
- **Docker Desktop** (or Docker Engine + Compose v2) for the full stack — allocate enough RAM for CockroachDB, Chronicle volumes, and optional high-VU k6 runs

## Quick start (Docker)

From the repo root:

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

Smoke test (expects client on host port **18080**):

```bash
./scripts/smoke-test.sh
```

Open **Grafana** at [http://localhost:3000](http://localhost:3000) (default `admin` / `admin`). Dashboards live under provisioning; k6 metrics use the **K6 Load Tests — RTP Ledger** dashboard once a k6 run has pushed data.

Optional k6 load run (dual remote-write to Prometheus and VictoriaMetrics — see [`infra/k6/README.md`](infra/k6/README.md)):

```bash
docker compose -f infra/docker/docker-compose.yml --profile k6 run --rm k6 /k6/scripts/rtp_load_test.js
```

## Configuration highlights

| Concern | Notes |
|---------|--------|
| **CockroachDB** | Server uses `CRDB_URL` — never commit credentials |
| **Chronicle paths** | Map and queue paths come from Spring config / env — environment-specific |
| **Money** | All balance arithmetic uses `BigDecimal` in application code |

## Development build

```bash
mvn -q -DskipTests compile
```

Run modules locally against your own NATS/CockroachDB (see each module’s `application.yml` and Docker Compose defaults for wiring).

## Documentation map

| File | Purpose |
|------|---------|
| [`CLAUDE.md`](CLAUDE.md) | Architecture locks, threading, NATS subjects, data models |
| [`TODO.md`](TODO.md) | Checkpoint tracker and STOP GATE protocol |
| [`infra/k6/README.md`](infra/k6/README.md) | k6 scenarios, concurrency suites, Grafana / metrics |
| [`.github/instructions/`](.github/instructions/) | Module-specific instructions for client, server, infra |

## License

No license file is included in this repository; treat usage as internal or add a `LICENSE` as appropriate for your organization.
