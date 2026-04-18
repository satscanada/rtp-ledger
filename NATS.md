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

## Quick verification

After NATS is listening:

```bash
curl -sS http://localhost:8222/varz | head
```

You should see JSON server stats. If the connection is refused, NATS is not up or the monitoring port differs from what you passed (`-m` / `-ms`).

Optional: install the [NATS CLI](https://github.com/nats-io/natscli) (`brew install nats-io/nats-tools/nats`) and run `nats server check` against your URL.

---

## Order of operations for end-to-end tests

1. Start **NATS** (this document).
2. Start **rtp-server** (CP-03) so it subscribes to `ledger.>` and `ledger.balance.>` and can reply to balance requests.
3. Start **rtp-client** (CP-02) so it connects to NATS and exposes HTTP on port **8080** (default).

Then use Postman or `curl` against the client: posting publishes to NATS; balance issues a request to the server over NATS.
