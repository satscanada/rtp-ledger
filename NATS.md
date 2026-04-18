# NATS — local setup for development

## Why you need it

- **rtp-client (CP-02)** opens a NATS connection at startup and publishes postings to `ledger.{region}.{accountId}`. If NATS is not reachable, the client will fail to start or fail publishes.
- **GET balance** also requires **rtp-server (CP-03)** running and subscribed to `ledger.balance.>`, in addition to NATS. NATS alone is not enough for a successful balance response.

Default URL used by the client: `nats://localhost:4222` (see `client/src/main/resources/application.yml`, key `rtp.client.nats.servers`).

---

## Option A — Docker (recommended)

Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) if you do not already have it.

Run a single-node NATS server with the HTTP monitoring port enabled (useful for metrics and health checks later):

```bash
docker run --rm --name nats-dev -p 4222:4222 -p 8222:8222 nats:2.10-alpine \
  -m 8222
```

Notes:

- **4222** — client and server connections.
- **8222** — monitoring HTTP (`http://localhost:8222`).
- This matches **core NATS** usage (no JetStream) as described in the project architecture.

Stop the container:

```bash
docker stop nats-dev
```

---

## Option B — Homebrew (macOS)

```bash
brew install nats-server
```

Run with monitoring on 8222:

```bash
nats-server -p 4222 -m 8222
```

Leave this terminal open, or run it under `tmux`/a service manager.

---

## Option C — Official binary

Download a release for your OS from [NATS Server releases](https://github.com/nats-io/nats-server/releases), install the binary on your `PATH`, then:

```bash
nats-server -p 4222 -m 8222
```

---

## Option D — Full lab stack (NATS + UI + apps, CP-05)

The project’s `infra/docker/docker-compose.yml` runs **NATS** (with a vendored `nats.conf` aligned to the [gastbob40/nats-ui](https://github.com/gastbob40/nats-ui) setup), **NATS UI** on host port **3010**, CockroachDB, **rtp-client** / **rtp-server** / **rtp-simulator**, Surveyor, Prometheus, and Grafana. The upstream one-command download is documented alongside port mapping notes in **`infra/docker/README-NATS-UI.md`**.

From the repo root:

```bash
cd infra/docker && docker compose up -d
```

Then open NATS monitoring on **8222**, NATS UI on **3010**, **rtp-client** on **8080**, CockroachDB Console on **28080**, Prometheus on **9091**, and Grafana on **3000**. Use `./scripts/smoke-test.sh` after the stack is up.

---

## Quick verification

After NATS is listening:

```bash
curl -sS http://localhost:8222/varz | head
```

You should see JSON server stats. If the connection is refused, NATS is not up or the monitoring port differs from what you passed (`-m` / `-ms`).

With **`infra/docker/docker-compose.yml`**, monitoring is on **8222** and WebSocket on **9222** (see `infra/docker/nats.conf`). Example WebSocket URL for local tools: **`ws://localhost:9222`**.

Optional: install the [NATS CLI](https://github.com/nats-io/natscli) (`brew install nats-io/nats-tools/nats`) and run `nats server check` against your URL.

---

## Order of operations for end-to-end tests

1. Start **NATS** (this document).
2. Start **rtp-server** (CP-03) so it subscribes to `ledger.>` and `ledger.balance.>` and can reply to balance requests.
3. Start **rtp-client** (CP-02) so it connects to NATS and exposes HTTP on port **8080** (default).

Then use Postman or `curl` against the client: posting publishes to NATS; balance issues a request to the server over NATS.
