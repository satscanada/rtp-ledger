# NATS + NATS UI (upstream recipe)

You can bootstrap NATS **and** the NATS UI using the upstream one-liner downloads:

```bash
curl -O https://raw.githubusercontent.com/gastbob40/nats-ui/main/docker/docker-compose.yml
curl -O https://raw.githubusercontent.com/gastbob40/nats-ui/main/docker/nats.conf
docker compose up -d
```

For this repo’s **integrated** stack, `infra/docker/docker-compose.yml` vendors `nats.conf` (same content intent) and runs:

- NATS (`nats:2-alpine`) with `-c /etc/nats/nats.conf` — ports **4222**, **8222**, **9222**
- NATS UI (`ghcr.io/gastbob40/nats-ui:latest`) — published on host **3010 → container 3000** (Grafana stays on **3000**)

This avoids port clashes with Grafana and avoids the upstream compose `build:` context.

---

## Building `rtp-client` / `rtp-server` / `rtp-simulator` images

Default **`Dockerfile`** only packages the runnable JAR (built on your machine). You **do not** need the `maven:*` image, which avoids failures when Docker Hub is slow or blocked behind a proxy.

From the **repository root**:

```bash
./infra/docker/build-local.sh
```

Or manually:

```bash
mvn -B -pl client,server,simulator -am package -DskipTests
cd infra/docker && docker compose build
```

If you prefer a **full in-container Maven build** (needs a working pull of `maven:3.9.9-eclipse-temurin-21-alpine`), set the compose build to use **`Dockerfile.maven`** for each `rtp-*` service, or run:

```bash
docker build -f infra/docker/Dockerfile.maven --build-arg MODULE=client -t rtp-ledger/rtp-client:latest ..
```

### NATS Surveyor logs `503 No responders` / statz errors

Surveyor polls **`$SYS.REQ.*`** (statz). That requires a **system account** on the NATS server and a client connection **as a SYS user**. This repo’s `nats.conf` defines **`SYS`** / **`APP`**, **`system_account: SYS`**, **`no_auth_user: rtp`** (passwordless app traffic → account **APP**), and Compose runs Surveyor as **`nats://sys:rtpSysDev01@nats:4222`**. After changing `nats.conf`, recreate **`rtp-nats`** and **`rtp-nats-surveyor`**.

### `rtp-server` crashes on Chronicle / `IllegalAccessException` (java.lang.reflect)

Chronicle Map and Queue need **`--add-opens`** on Java 17+ (JPMS). Compose sets **`JDK_JAVA_OPTIONS`** on **`rtp-server`** (Temurin Alpine mishandles **`JAVA_TOOL_OPTIONS`** with **`--add-opens`**). For a bare `java -jar server.jar`, export the same opens via **`JDK_JAVA_OPTIONS`** or **`JAVA_TOOL_OPTIONS`**, or use **`mvn -pl server spring-boot:run`** (see `server/pom.xml` `jvmArguments`).

### Proxy timeout (`192.168.65.1:3128`, `DeadlineExceeded`)

Docker Desktop is routing registry traffic through an HTTP proxy that is not responding. Fix **Docker Desktop → Settings → Resources → Proxies** (clear or correct the proxy), or disable VPN/firewall rules blocking Docker. Using **`build-local.sh`** avoids pulling the Maven base image; you still need **`eclipse-temurin:21-jre-alpine`** once unless it is already cached.

### Host ports (avoid clashes)

| Service | Host | Notes |
|--------|------|--------|
| **NATS** | 4222, **8222**, **9222** | Published on the **Docker host**. See **NATS UI networking** below — do not use the hostname `nats` from your browser. |
| **rtp-client** | **18080** | HTTP API → container **8080** (avoids host **8080** clashes) |
| CockroachDB Console | **28080** | Maps to container **8080** |
| Prometheus | **9091** | Maps to container **9090** (use **9091** if something else already uses 9090 on the host) |
| Grafana | 3000 | |
| NATS UI | 3010 | |

Inside the Docker network, Grafana still talks to Prometheus at `http://prometheus:9090`.

### NATS UI networking (why `localhost` fails)

[gastbob40/nats-ui](https://github.com/gastbob40/nats-ui) is a **static SPA** served by nginx. WebSocket and monitoring calls run **in your browser**, not inside the `nats-ui` container.

1. **`localhost` / `127.0.0.1` mean “this machine”** — the one running the browser. They do **not** resolve to the NATS container. The Compose DNS name **`nats`** only works **between containers**; your browser cannot use `nats://nats:4222` unless you add fake DNS (e.g. `/etc/hosts`) pointing `nats` at `127.0.0.1`.

2. **Browser on the same machine as Docker** — Open the UI at **`http://localhost:3010`** (or `http://127.0.0.1:3010`). In the app **Settings**, point NATS at the **host-published** ports, for example:
   - **Client:** `nats://127.0.0.1:4222`
   - **Monitoring / HTTP:** `http://127.0.0.1:8222`
   - **WebSocket:** `ws://127.0.0.1:9222`  
   Prefer **`127.0.0.1`** over **`localhost`** if WebSocket still fails (some setups resolve `localhost` to IPv6 only).

3. **Safari on macOS** — Some builds behave badly with **`localhost`** / **`127.0.0.1`** for WebSocket or mixed-origin calls to NATS. Use your Mac’s **Bonjour hostname** (shown in **Sharing** settings), e.g. **`your-machine-name.local`**, for client, monitoring, and WebSocket URLs — same ports (**4222**, **8222**, **9222**). Example WebSocket: `ws://your-machine-name.local:9222`.

4. **Browser on another device** (phone, teammate’s laptop) — If you open **`http://<docker-host-LAN-ip>:3010`**, replace every endpoint with **that host’s LAN IP**, not `localhost`. Example: `ws://192.168.1.42:9222`. Open firewall rules for **4222**, **8222**, and **9222** on the Docker host.

5. **Sanity checks:** `curl -sS http://127.0.0.1:8222/varz | head` and `lsof -i :9222` on the host should show NATS listening after `docker compose up`.
