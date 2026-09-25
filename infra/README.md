# Infrastructure

This directory contains the local infrastructure required to run the **payment-platform** demo:
`auth-service`, `fraud-service`, and `ledger-service` communicating over REST and Kafka
(transactional outbox pattern), backed by Postgres, Redis, and a full observability stack.

Managed with Docker Compose, the stack includes:

- **auth-service**, **fraud-service**, **ledger-service** (optional overlay — see
  [Supported Run Modes](#supported-run-modes))
- **PostgreSQL 16** — one instance, three databases (`auth_db`, `fraud_db`, `ledger_db`) + pgAdmin
- **Kafka** — 3-broker KRaft cluster (`kafka-1`/`kafka-2`/`kafka-3`) + Kafka UI + kafka-exporter
- **Redis** — response/idempotency cache + RedisInsight + redis_exporter
- **OpenTelemetry Collector** + **Tempo** (distributed tracing)
- **Prometheus** + **Grafana** (metrics dashboards, pre-provisioned)
- **cAdvisor** (per-container resource metrics)

This guide explains how to start, inspect, stop, reset, and troubleshoot the local platform
environment.

For the complete application setup and demo walkthrough, see:

- [Getting Started Guide](../docs/getting-started/README.md)
- [Development Guide](../docs/getting-started/development.md)
- [Demo Guide](../docs/getting-started/demo-guide.md)

---

## Table of Contents

- [Directory Contents](#directory-contents)
- [Supported Run Modes](#supported-run-modes)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Compose Files](#compose-files)
- [Start the Platform](#start-the-platform)
    - [Start with Published Images](#start-with-published-images)
    - [Start with Locally Built Images](#start-with-locally-built-images)
    - [Start Infrastructure Only](#start-infrastructure-only)
- [Verify the Platform](#verify-the-platform)
    - [Check Container Status](#check-container-status)
    - [Check Service Health](#check-service-health)
    - [Check Logs](#check-logs)
    - [Check Infrastructure Connectivity](#check-infrastructure-connectivity)
- [Service Endpoints](#service-endpoints)
- [Infrastructure Components](#infrastructure-components)
- [Kafka Topics](#kafka-topics)
- [Observability](#observability)
    - [Grafana](#grafana)
    - [Prometheus](#prometheus)
    - [OpenTelemetry](#opentelemetry)
    - [Distributed Tracing](#distributed-tracing)
- [Common Docker Compose Commands](#common-docker-compose-commands)
- [Stop the Platform](#stop-the-platform)
- [Reset the Local Environment](#reset-the-local-environment)
- [Rebuild Images](#rebuild-images)
- [Troubleshooting](#troubleshooting)
- [Resource and Operational Notes](#resource-and-operational-notes)
- [Related Documentation](#related-documentation)
- [Quick Reference](#quick-reference)

---

## Directory Contents

```text
infra/
├── README.md                    # this file
├── docker/
│   ├── docker-compose.yml       # base infra stack (postgres, redis, kafka x3, otel, prometheus, grafana, tempo, ...)
│   └── docker-compose.app.yml   # overlay adding auth-service / fraud-service / ledger-service containers
├── postgres/
│   └── init/01-create-databases.sql   # creates auth_db, fraud_db, ledger_db on first startup
├── pdadmin/
│   ├── servers.json              # pre-registered pgAdmin server connection
│   └── pgpass                    # pgAdmin auto-login credentials file
├── kafka/
│   └── create-topics.sh          # creates auth.events + auth.events.ledger.dlt (run by kafka-topic-init)
├── otel/
│   ├── Dockerfile                 # builds the health-check-enabled otel-collector-contrib image
│   └── otel-collector-config.yml  # OTLP receiver -> Tempo exporter + span-metrics for Prometheus
├── tempo/
│   └── tempo.yml                  # Tempo trace storage/query config
├── prometheus/
│   └── prometheus.yml             # scrape targets for all services + exporters + cAdvisor
└── grafana/
    ├── dashboards/                 # provisioned dashboard JSON (see Observability)
    ├── provisioning/
    │   ├── datasources/            # auto-provisions Prometheus + Tempo data sources
    │   └── dashboards/             # auto-provisions the dashboards/ folder above
    └── scripts/
```

| File or directory                    | Purpose                                                          |
|---------------------------------------|-------------------------------------------------------------------|
| `docker/docker-compose.yml`           | Base infra stack (Postgres, Redis, Kafka, observability)          |
| `docker/docker-compose.app.yml`       | Overlay adding auth/fraud/ledger-service containers                |
| `postgres/init/`                      | First-boot SQL run by the official Postgres image entrypoint       |
| `pdadmin/`                            | pgAdmin auto-registered connection to the `postgres` service        |
| `kafka/create-topics.sh`              | Idempotent topic creation, run once by the `kafka-topic-init` container |
| `otel/`                                | Custom OTel Collector image + config (adds a health-check binary)  |
| `tempo/tempo.yml`                     | Tempo backend configuration (local storage, retention)             |
| `prometheus/prometheus.yml`           | Scrape configs for app services, exporters, and cAdvisor            |
| `grafana/dashboards/`                 | Pre-built dashboard JSON, auto-provisioned on Grafana startup       |
| `grafana/provisioning/`               | Grafana data-source and dashboard auto-provisioning definitions     |

There is no `docker-compose.local.yml` or `.env`/`.env.example` in this project — see
[Configuration](#configuration) for how image tags, ports, and credentials are actually
overridden here (shell environment variables with sane defaults baked into the Compose files).

---

## Supported Run Modes

| Mode                  | Best for                      | Application services                          | Infrastructure               |
|------------------------|--------------------------------|------------------------------------------------|-------------------------------|
| Published images      | Fastest demo setup             | Pulled from Docker Hub (`shirongquan/*:1.0.0`) | `docker-compose.yml` + `docker-compose.app.yml` |
| Locally built images  | Testing local code changes     | Built from the repo via each service's `Dockerfile` | `docker-compose.yml` + `docker-compose.app.yml` |
| IDE/local services    | Debugging individual services  | Run via `mvn spring-boot:run` or from the IDE  | `docker-compose.yml` only    |

### Published images

Run the platform without building anything locally — pulls `shirongquan/auth-service:1.0.0`,
`shirongquan/fraud-service:1.0.0`, and `shirongquan/ledger-service:1.0.0` from Docker Hub. Fastest
startup, reproducible versions, ideal for a first demo.

### Locally built images

Build each service's jar and Docker image from the current checkout (see
[Start with Locally Built Images](#start-with-locally-built-images)), then let Compose pick up the
freshly built local image (same tag as the published one, by default) instead of pulling.

### IDE/local services

Start only the infrastructure containers (`docker-compose.yml`, no app overlay), then run
`auth-service`/`fraud-service`/`ledger-service` directly from an IDE or `mvn spring-boot:run` so you
can set breakpoints. See the [Development Guide](../docs/getting-started/development.md) and
[Local Development Guide](../docs/development/local-setup.md) for the exact commands.

---

## Prerequisites

- Docker Desktop (or Docker Engine) + Docker Compose v2
- `curl` for health checks
- `jq` (optional, for pretty-printing JSON responses)
- Java 21 + Maven 3.9+ — only needed for **locally built images** or **IDE/local services** mode
- `kcat` (`brew install kcat`) — optional, for inspecting/producing Kafka messages (see
  [Runbook](../docs/development/runbook.md))
- `psql` / `redis-cli` — optional, for inspecting Postgres/Redis directly (also available inside
  the containers themselves, see [Check Infrastructure Connectivity](#check-infrastructure-connectivity))

Verify Docker and Docker Compose:

```bash
docker --version
docker compose version
docker info
```

If `docker info` fails, start Docker Desktop (or the Docker daemon) before continuing.

---

## Configuration

Unlike a generic template, this project does **not** use an `infra/.env` / `.env.example` file.
Every service in `docker-compose.yml`/`docker-compose.app.yml` has a sensible default baked in
(credentials, ports, image tags), and the handful of values you'd realistically want to override
are exposed as **shell environment variables** at `docker compose up` time:

| Variable                 | Default                                | Used for                                    |
|----------------------------|-----------------------------------------|-----------------------------------------------|
| `AUTH_SERVICE_IMAGE`       | `shirongquan/auth-service:1.0.0`        | `auth-service` image reference               |
| `AUTH_SERVICE_PORT`        | `9000`                                  | `auth-service` host port + `SERVER_PORT` + healthcheck URL |
| `FRAUD_SERVICE_IMAGE`      | `shirongquan/fraud-service:1.0.0`       | `fraud-service` image reference              |
| `FRAUD_SERVICE_PORT`       | `9010`                                  | `fraud-service` host port + `SERVER_PORT` + healthcheck URL |
| `FRAUD_SERVICE_PROFILE`    | `dev`                                   | Enables `/internal/test/failure-mode` chaos endpoint used by `load-tests/` |
| `LEDGER_SERVICE_IMAGE`     | `shirongquan/ledger-service:1.0.0`      | `ledger-service` image reference              |
| `LEDGER_SERVICE_PORT`      | `9020`                                  | `ledger-service` host port + `SERVER_PORT` + healthcheck URL |
| `GRAFANA_ADMIN_USER`       | `admin`                                 | Grafana login username                       |
| `GRAFANA_ADMIN_PASSWORD`   | `password`                              | Grafana login password                       |

Example — change ports and disable the fraud-service test-mode endpoint:

```bash
AUTH_SERVICE_PORT=9100 FRAUD_SERVICE_PORT=9110 LEDGER_SERVICE_PORT=9120 \
FRAUD_SERVICE_PROFILE= \
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

Postgres (`postgres`/`postgres`), Redis (no auth), and the Kafka cluster (`PLAINTEXT`, no ACLs) use
fixed, hardcoded local-only credentials — see [Security](#security) below. These are intentionally
**not** overridable via environment variables since this stack is local-demo-only, never deployed
with real credentials or real payment data.

---

## Compose Files

Two Compose files, both under `infra/docker/`:

```text
infra/docker/docker-compose.yml       # base infra: postgres, pgadmin, redis, redisinsight,
                                       # redis_exporter, kafka-1/2/3, kafka-ui, kafka-topic-init,
                                       # kafka-exporter, cadvisor, otel-collector, tempo, prometheus, grafana
infra/docker/docker-compose.app.yml   # overlay: auth-service, fraud-service, ledger-service
```

They are always combined with `-f`, in this order (the overlay must come second so its service
definitions layer on top of the base file):

```bash
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  config --quiet && echo "CONFIG OK"
```

`config` renders (and validates) the final merged configuration — useful for confirming that an
environment variable override actually took effect before starting containers.

---

## Start the Platform

Run commands from the **repository root** unless otherwise stated.

### Start with Published Images

This is the recommended mode for a first demo.

```bash
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  pull
```

Start the complete stack:

```bash
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  up -d
```

Follow the startup logs:

```bash
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  logs -f
```

Start only selected services (and their dependencies are pulled in automatically by Compose):

```bash
docker compose -f infra/docker/docker-compose.yml up -d postgres redis kafka-1 kafka-2 kafka-3
```

If your Compose version supports `--wait`, use it to block until healthchecks pass:

```bash
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  up -d --wait
```

Otherwise use the commands in [Verify the Platform](#verify-the-platform).

### Start with Locally Built Images

Build each service's jar, then its image, from the repo root (image tags match the
`docker-compose.app.yml` defaults, so no override is needed unless you want a different tag):

```bash
mvn -pl auth-service,fraud-service,ledger-service -am package -DskipTests

docker build -f auth-service/Dockerfile   -t shirongquan/auth-service:1.0.0   .
docker build -f fraud-service/Dockerfile  -t shirongquan/fraud-service:1.0.0  .
docker build -f ledger-service/Dockerfile -t shirongquan/ledger-service:1.0.0 .
```

Start the platform — Compose uses the freshly built local images since the tags match:

```bash
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  up -d
```

Rebuild and restart a single service after a code change:

```bash
mvn -pl ledger-service -am package -DskipTests
docker build -f ledger-service/Dockerfile -t shirongquan/ledger-service:1.0.0 .
docker compose \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.app.yml \
  up -d --force-recreate --no-deps ledger-service
```

To keep locally built images separate from the published ones, use a distinct tag/variable
override instead of overwriting `:1.0.0`:

```bash
docker build -f ledger-service/Dockerfile -t shirongquan/ledger-service:local .
LEDGER_SERVICE_IMAGE=shirongquan/ledger-service:local \
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d ledger-service
```

### Start Infrastructure Only

Use this mode when running `auth-service`/`fraud-service`/`ledger-service` from an IDE or via
`mvn spring-boot:run` (see the [Local Development Guide](../docs/development/local-setup.md)).

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

This starts Postgres, Redis, the 3-broker Kafka cluster (+ topic init), the OTel Collector, Tempo,
Prometheus, Grafana, pgAdmin, RedisInsight, Kafka UI, and the exporters/cAdvisor — everything
except the three application services.

Host-run services connect over the **published host ports**, not the Compose service DNS names:

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/auth_db
SPRING_DATA_REDIS_HOST=localhost
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092,localhost:9094,localhost:9095
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
```

`prometheus.yml` already scrapes host-run services via `host.docker.internal:<port>` (see
[Prometheus](#prometheus)), so metrics work the same way in this mode as in the full-container mode.

---

## Verify the Platform

After startup, verify that containers are running and application services have passed their
health checks.

### Check Container Status

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml ps
```

Include stopped containers:

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml ps -a
```

A healthy platform shows every service as `Up` (most report `Up (healthy)` once their healthcheck
passes).

### Check Service Health

```bash
curl -s http://localhost:9000/actuator/health | jq   # auth-service
curl -s http://localhost:9010/actuator/health | jq   # fraud-service
curl -s http://localhost:9020/actuator/health | jq   # ledger-service
```

Expected response:

```json
{ "status": "UP" }
```

A service may briefly report `DOWN` while its dependencies (Postgres, Kafka, Redis, the OTel
Collector) are still starting — Compose's `depends_on: condition: service_healthy` already gates
startup order, but the app's own Actuator health check can lag a few seconds behind.

### Check Logs

```bash
# whole stack
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml logs -f

# one service
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml logs -f ledger-service

# last 200 lines, or since a relative time
docker compose -f infra/docker/docker-compose.yml logs --tail=200 kafka-1
docker compose -f infra/docker/docker-compose.yml logs --since=10m postgres
```

### Check Infrastructure Connectivity

**Postgres:**

```bash
docker exec -it postgres pg_isready -U postgres -d postgres
docker exec -it postgres psql -U postgres -d postgres -c "\l"
```

Databases `auth_db`, `fraud_db`, and `ledger_db` should all be listed (created by
`postgres/init/01-create-databases.sql` on first startup).

**Redis:**

```bash
docker exec -it redis redis-cli PING
# -> PONG

docker exec -it redis redis-cli SET test-key hello
docker exec -it redis redis-cli GET test-key
# -> hello
```

**Kafka** (3-broker cluster; `kafka-1` is the bootstrap server used inside the compose network):

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --list
```

Expect `auth.events` and `auth.events.ledger.dlt` (created once by the `kafka-topic-init` container
— check `docker compose logs kafka-topic-init` if they're missing).

**Prometheus:**

1. Open `http://localhost:9090/targets` — all jobs (`prometheus`, `otel-collector-spanmetrics`,
   `auth-service`, `fraud-service`, `ledger-service`, `kafka-exporter`, `cadvisor`,
   `redis_exporter`) should show state `UP`.
2. Run a basic query in the UI: `up` — every target should return `1`.

**Grafana:**

1. Open `http://localhost:3000` and log in with `admin` / `password` (or your
   `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` overrides).
2. The **Prometheus** and **Tempo** data sources are already auto-provisioned (via
   `infra/grafana/provisioning/datasources/`) — no manual "Add data source" step needed.
3. The **Payment Platform** dashboard folder (auto-provisioned from `infra/grafana/dashboards/`)
   should already contain the dashboards listed in [Observability](#observability).

**Logs, if any of the above fail:**

```bash
docker compose -f infra/docker/docker-compose.yml logs redis prometheus grafana
```

---

## Service Endpoints

| Component               | Local endpoint                     | Purpose                                          |
|--------------------------|--------------------------------------|----------------------------------------------------|
| auth-service            | `http://localhost:9000`             | Account, authorise/capture/reverse API             |
| fraud-service           | `http://localhost:9010`             | Synchronous fraud-check API (internal, called by auth-service) |
| ledger-service          | `http://localhost:9020`             | Read-only ledger/event-timeline query API           |
| PostgreSQL              | `localhost:5432`                    | `auth_db` / `fraud_db` / `ledger_db`                |
| pgAdmin                 | `http://localhost:5050`             | Postgres admin UI (`admin@example.com` / `admin`, pre-registered server) |
| Redis                   | `localhost:6379`                    | Idempotency response cache, fraud velocity counters |
| RedisInsight            | `http://localhost:5540`             | Redis admin/inspection UI                           |
| Kafka (broker 1/2/3)    | `localhost:9092` / `:9094` / `:9095` | 3-broker KRaft cluster external listeners           |
| Kafka UI                | `http://localhost:9091`             | Topic/consumer-group browser                        |
| Grafana                 | `http://localhost:3000`             | Metrics dashboards                                  |
| Prometheus              | `http://localhost:9090`             | Metrics queries/targets                             |
| OpenTelemetry Collector | `localhost:4317` (gRPC) / `:4318` (HTTP) | OTLP telemetry ingestion                       |
| Tempo                   | `http://localhost:3200`             | Trace query API (used by Grafana Explore)           |
| cAdvisor                | `http://localhost:8085`             | Per-container resource metrics                      |
| kafka-exporter          | `http://localhost:9308`             | Kafka broker/topic/consumer-group metrics for Prometheus |
| redis_exporter          | `http://localhost:9121`             | Redis metrics for Prometheus                         |

### Internal versus host addresses

Containers on the Compose network talk to each other by **service name**:

```text
postgres:5432
redis:6379
kafka-1:9092 / kafka-2:9092 / kafka-3:9092   (internal listener)
otel-collector:4317 / :4318
```

Anything running **outside** Docker (an IDE-run service, a host `psql`/`redis-cli`, `kcat`) uses
the **published host port** instead — for Kafka specifically, the external listener ports
(`9092`/`9094`/`9095`) differ from the internal ones (all `9092` on their own hostname). Never use
`localhost` from inside one container to reach another — `localhost` there refers to that same
container.

---

## Infrastructure Components

### PostgreSQL

One `postgres:16` instance (container `postgres`), hosting three databases created by
`postgres/init/01-create-databases.sql`:

- `auth_db` — accounts, authorisations, authorisation events, outbox
- `fraud_db` — fraud evaluations
- `ledger_db` — ledger entries, ledger event log, `processed_event` dedup table

Data persists in the `postgres_data` named volume across restarts (until `down -v`).

```bash
docker exec -it postgres pg_isready -U postgres -d postgres
docker exec -it postgres psql -U postgres -d auth_db -c "\dt"
```

**pgAdmin** (`http://localhost:5050`, `admin@example.com` / `admin`) comes with a server connection
already registered (`infra/pdadmin/servers.json` + `pgpass`) — no manual connection setup needed.

Do not point this stack at production data; it is local-demo-only (see [Security](#security)).

### Kafka

A genuine 3-broker KRaft cluster (`kafka-1`/`kafka-2`/`kafka-3`, replication factor 3), used for the
transactional-outbox event flow between `auth-service` and `ledger-service`:

- `auth.events` — authorisation lifecycle events (6 partitions, keyed by authorisation id)
- `auth.events.ledger.dlt` — dead-letter topic for `ledger-service`'s consumer

Both topics are created once by the `kafka-topic-init` container (`infra/kafka/create-topics.sh`),
which runs to completion after all three brokers report healthy. See:

- [Kafka Topics](../docs/eventing/kafka-topics.md)
- [Outbox Pattern](../docs/eventing/outbox-pattern.md)

```bash
# list topics
docker exec -it kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --list

# describe a topic (partition/replica assignment)
docker exec -it kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:9092 --describe --topic auth.events

# consume from the beginning (dev/test topics only — can be noisy)
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic auth.events --from-beginning
```

**Kafka UI** (`http://localhost:9091`) gives a browsable view of topics, partitions, and consumer
group lag without the CLI.

### Redis

`redis:8.6-alpine`, used by `auth-service` for the idempotency response cache and by
`fraud-service` for IP/account velocity-rule counters (no authentication configured — local-demo
only).

```bash
docker exec -it redis redis-cli ping
docker exec -it redis redis-cli scan 0
docker exec -it redis redis-cli keys 'fraud:ip:*'
```

**RedisInsight** (`http://localhost:5540`) gives a browsable UI over the same data.

### OpenTelemetry Collector

A custom-built `otel-collector-contrib` image (`infra/otel/Dockerfile`, adds a health-check
binary) that receives OTLP traces from all three services and forwards them to Tempo, plus exports
span metrics for Prometheus.

| Protocol / port | Purpose                              |
|------------------|----------------------------------------|
| OTLP gRPC `4317` | Trace ingestion (used by containerized services) |
| OTLP HTTP `4318` | Trace ingestion (used by IDE/host-run services) |
| `8889`           | Span-metrics endpoint, scraped by Prometheus |
| `13133`          | `health_check` extension (container healthcheck) |

```bash
docker compose -f infra/docker/docker-compose.yml logs -f otel-collector
```

A service that starts but produces no traces should be checked for: correct OTLP endpoint/protocol
for its run mode (container vs. host), network reachability, and Collector startup errors.

### Prometheus

Scrapes application and infrastructure metrics per `infra/prometheus/prometheus.yml`:

| Job                          | Target                                  |
|--------------------------------|--------------------------------------------|
| `prometheus`                  | `prometheus:9090` (self)                    |
| `otel-collector-spanmetrics`  | `otel-collector:8889`                       |
| `auth-service`                | `host.docker.internal:9000/actuator/prometheus` |
| `fraud-service`               | `host.docker.internal:9010/actuator/prometheus` |
| `ledger-service`               | `host.docker.internal:9020/actuator/prometheus` |
| `kafka-exporter`               | `kafka-exporter:9308`                       |
| `cadvisor`                     | `cadvisor:8080`                             |
| `redis_exporter`                | `redis_exporter:9121`                       |

The three app-service jobs target `host.docker.internal` (not the container DNS name) because that
also works when the services run as containers via `docker-compose.app.yml` — their ports are
published to the host either way, so Prometheus reaches them the same way in every run mode.

### Grafana

Dashboards and data sources are **fully auto-provisioned** on startup from
`infra/grafana/provisioning/` and `infra/grafana/dashboards/` — no manual setup required. Dashboard
JSON files:

| Dashboard                        | Covers                                                        |
|------------------------------------|------------------------------------------------------------------|
| `payment-platform-overview.json` | HTTP throughput/errors, auth/capture/reverse rates, outbox lag/backlog, idempotency & concurrency-conflict counters, fraud decisions, circuit-breaker state |
| `kafka-exporter-overview.json`   | Broker/topic/consumer-group metrics, DLT publish rate            |
| `redis-overview.json`             | Redis memory, ops/sec, hit rate                                 |
| `spring-boot-stats.json`          | Per-service Spring Boot/HTTP metrics                             |
| `JVM(Micrometer).json`            | JVM heap, GC, threads for each service                          |

```bash
docker compose -f infra/docker/docker-compose.yml logs -f grafana
```

---

## Kafka Topics

Topics are created **once**, automatically, by the `kafka-topic-init` container
(`infra/kafka/create-topics.sh`), which runs after all three brokers report healthy and exits on
success (`service_completed_successfully` — both app services' `depends_on` block on this).

```bash
docker compose -f infra/docker/docker-compose.yml logs kafka-topic-init
```

Manually re-run topic creation (idempotent — uses `--if-not-exists`):

```bash
docker exec -it kafka-1 /bin/sh /create-topics.sh
```

Verify:

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --list
```

If `auth.events` / `auth.events.ledger.dlt` are missing:

1. Check `kafka-1`/`kafka-2`/`kafka-3` are all healthy (`docker compose ps`).
2. Check `kafka-topic-init` logs for a creation error.
3. Confirm the bootstrap server (`kafka-1:9092`, the internal listener) is reachable from inside
   the network.
4. Re-run the script manually (above).
5. Review [Kafka Topics](../docs/eventing/kafka-topics.md).

---

## Observability

```text
auth-service / fraud-service / ledger-service
        │
        ├── metrics ──────> Prometheus ──────> Grafana
        │
        ├── traces ───────> OpenTelemetry Collector ──────> Tempo (queried via Grafana Explore)
        │
        └── logs ─────────> stdout / `docker compose logs`
```

### Grafana

Open `http://localhost:3000`, log in with `admin` / `password` (or your
`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` overrides), and open the **Payment Platform** folder.
See the dashboard list in [Infrastructure Components → Grafana](#grafana) above, and:

- [Telemetry](../docs/observability/telemetry.md)
- [Alerts and SLOs](../docs/observability/alerts-slos.md)
- [Observability Runbook](../docs/observability/runbook.md)

### Prometheus

Open `http://localhost:9090` to verify scrape targets, run ad-hoc queries, or debug missing
dashboard data. Example queries actually used by this platform's dashboards:

```promql
up

sum by (application, status) (increase(http_server_requests_seconds_count{uri!~".*(prometheus|health).*"}[1m]))

histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))

auth_outbox_backlog

rate(ledger_kafka_dlt_published_total[5m])
```

### OpenTelemetry

Containerized services export to the Collector's gRPC endpoint; host/IDE-run services use the HTTP
endpoint against the published host port:

```dotenv
# containerized (docker-compose.app.yml default)
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces

# host/IDE-run
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
```

`TRACING_SAMPLING_PROBABILITY=1.0` is set for every service in this stack — every request is
traced, which is appropriate for a low-traffic demo but would need tuning for production volume.

### Distributed Tracing

Query Tempo via **Grafana Explore** (`http://localhost:3000` → Explore → Tempo datasource), using
TraceQL. See the [Runbook](../docs/development/runbook.md#tempo-trace-queries-traceql) for the full
set of queries actually used against this platform, e.g.:

```traceql
{ resource.service.name = "auth-service" }
{ resource.service.name = "auth-service" && status = error }
{ resource.service.name = "auth-service" && duration > 200ms }
```

A successful authorise-then-capture trace follows:

```text
POST /authorisations (auth-service)
  ├── fraud-service: POST /fraud/check
  ├── Postgres transaction (authorisation + account + outbox row)
  └── (async, later) outbox.kafka.publish -> auth.events
        └── ledger-service consumer -> ledger_db transaction
```

Note: the outbox-publish span is a **separate trace** linked back to the original request trace via
a span Link, not a direct parent/child span — see the
[Runbook](../docs/development/runbook.md#outbox---kafka---ledger-service-span-link) for how to
follow that link in Grafana.

If traces are missing: confirm the OTLP endpoint/protocol matches the run mode (container vs.
host), check `docker compose logs -f otel-collector`, and generate a fresh request (traces only
appear after the stack has fully started).

---

## Common Docker Compose Commands

```bash
# list services
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml config --services

# validate the merged configuration
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml config --quiet

# start in the foreground (Ctrl+C to stop)
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up

# restart / stop a single service
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml restart ledger-service
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml stop ledger-service

# remove stopped containers
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml rm -f

# pull newer published images
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml pull

# resource usage / inspect a container
docker stats
docker inspect auth-service

# shell into a container
docker exec -it auth-service sh
docker exec -it postgres bash
```

---

## Stop the Platform

Stop containers, keep named volumes (data survives):

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down
```

Stop **and** delete all volumes (Postgres data, Kafka logs, Grafana/Prometheus state, Redis data):

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down -v
```

Use `down -v` only when you intentionally want a clean slate.

---

## Reset the Local Environment

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down -v
docker image prune                     # optional: drop dangling locally built images
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

A full reset (`down -v`) deletes:

- all `auth_db`/`fraud_db`/`ledger_db` rows (payments, ledger entries, outbox rows, fraud evaluations)
- all Kafka topic data (`auth.events`, `auth.events.ledger.dlt`) and the topics themselves
- Redis keys (idempotency cache, velocity counters)
- Grafana/Prometheus/Tempo local state (dashboards themselves are re-provisioned from disk, but ad
  hoc changes/history are lost)

Do not reset if you need to preserve data for investigation.

---

## Rebuild Images

```bash
# rebuild one service without cache, then recreate it
mvn -pl ledger-service -am package -DskipTests
docker build --no-cache -f ledger-service/Dockerfile -t shirongquan/ledger-service:1.0.0 .
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml \
  up -d --force-recreate --no-deps ledger-service

# rebuild all three
for svc in auth-service fraud-service ledger-service; do
  docker build --no-cache -f "$svc/Dockerfile" -t "shirongquan/$svc:1.0.0" .
done
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml \
  up -d --force-recreate auth-service fraud-service ledger-service
```

---

## Troubleshooting

### A container exits immediately

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml ps -a
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml logs <service-name>
```

Common causes: a required dependency isn't healthy yet (check `depends_on` order), a port already
in use on the host, or (for locally built images) a stale/missing jar from a skipped `mvn package`.

### A service cannot connect to PostgreSQL

```bash
docker compose -f infra/docker/docker-compose.yml ps postgres
docker exec -it postgres pg_isready -U postgres -d postgres
```

Inside Docker Compose the host is `postgres` (not `localhost`); from the host/IDE it's
`localhost:5432`. Check the database name matches the service (`auth_db`/`fraud_db`/`ledger_db`)
and credentials are `postgres`/`postgres`.

### A service cannot connect to Kafka

```bash
docker compose -f infra/docker/docker-compose.yml ps kafka-1 kafka-2 kafka-3
docker compose -f infra/docker/docker-compose.yml logs kafka-1
```

From another container: `kafka-1:9092,kafka-2:9092,kafka-3:9092` (internal listener). From the
host: `localhost:9092,localhost:9094,localhost:9095` (external listener — note the different port
numbers per broker). Confirm the topics exist (see [Kafka Topics](#kafka-topics)).

### Kafka is running but consumers can't consume messages

Check consumer group name, that the topic has messages (`kafka-console-consumer.sh
--from-beginning`), and consumer lag in Kafka UI (`http://localhost:9091`) or the Kafka dashboard in
Grafana. `ledger-service`'s consumer also requires specific Kafka **headers** on every record — see
the [Runbook](../docs/development/runbook.md#generating-mock-data-for-ledger-service-kafka-consumer--dlt-metrics).

### Ports are already in use

```bash
lsof -i :9000   # macOS/Linux
```

```powershell
Get-NetTCPConnection -LocalPort 9000   # Windows PowerShell
```

Either stop the conflicting process, or override the relevant `*_SERVICE_PORT` variable (app
services) or edit the `ports:` mapping directly in `docker-compose.yml` (infra components, which
don't have port env-var overrides today).

### Grafana has no data

1. Confirm Grafana and Prometheus are both healthy (`docker compose ps`).
2. Open `http://localhost:9090/targets` — every job should be `UP`.
3. If an app-service target is down, confirm it's actually running and its Actuator Prometheus
   endpoint is reachable at `host.docker.internal:<port>/actuator/prometheus` from inside the
   `prometheus` container.
4. Check the dashboard's selected time range includes recent data.

```bash
docker compose -f infra/docker/docker-compose.yml logs grafana prometheus
```

### Traces do not appear

Check the OTLP endpoint matches the run mode (`otel-collector:4318` for containers,
`localhost:4318` for host/IDE-run services), that `otel-collector` and `tempo` are both healthy,
and that a fresh request was made after the stack finished starting.

```bash
docker compose -f infra/docker/docker-compose.yml logs -f otel-collector tempo
```

### The outbox is growing

See the dedicated recovery runbook — includes detect/triage/retry/manual-replay steps and the
exact SQL to requeue a `FAILED` outbox row:

- [Outbox Backlog Recovery](../docs/flows/outbox-backlog-recovery.md)
- Grafana panel: `auth_outbox_backlog` / `auth_outbox_publish_lag_seconds` (Payment Platform
  Overview dashboard)

### The dead-letter topic is growing

`auth.events.ledger.dlt` grows when `ledger-service` receives a genuinely unsupported/malformed
event (non-retryable). Do not delete DLT messages before identifying the root cause — see:

- [Failure Scenarios](../docs/flows/failure-scenarios.md)
- [Runbook — DLT reproduction steps](../docs/development/runbook.md#generating-mock-data-for-ledger-service-kafka-consumer--dlt-metrics)

---

## Resource and Operational Notes

The complete stack (3 Kafka brokers + Postgres + Redis + 3 app services + full observability
stack) is heavier than the application services alone. If your machine is slow:

- Use [Start Infrastructure Only](#start-infrastructure-only) and run just the one service you're
  actively working on from the IDE, instead of the full `docker-compose.app.yml` overlay.
- Reduce trace sampling (`TRACING_SAMPLING_PROBABILITY`) if trace volume feels heavy for local
  hardware (defaults to `1.0`, i.e. every request).
- Check `docker stats` for CPU/memory pressure — the 3-broker Kafka cluster is usually the biggest
  consumer of the infra-only stack.

### Local data

Named Docker volumes persist Postgres, Kafka, Redis, Grafana, Prometheus, and Tempo data across
restarts. This is convenient for iterative development but can leave stale state between demo runs
(e.g. depleted account balances from repeated load-test runs). Reset only when needed:

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down -v
```

### Security

This Compose stack is optimized for **local development and demos**, favoring simplicity and fast
iteration:

- Postgres/pgAdmin/Grafana all use fixed, well-known local credentials (`postgres`/`postgres`,
  `admin@example.com`/`admin`, `admin`/`password`).
- Redis and Kafka run with open, unauthenticated listeners (`PLAINTEXT`) to keep local setup simple.
- TLS is not configured, since traffic stays on the local Docker network.
- Application-level authentication/authorization is tracked as a roadmap item — see
  [Trust Boundaries](../docs/architecture/trust-boundaries.md).

See the security documentation for the platform's current security posture:

- [Threat Model](../docs/security/threat-model.md)
- [Data Protection](../docs/security/data-protection.md)
- [Audit and Compliance](../docs/security/audit-compliance.md)

---

## Related Documentation

### Getting started

- [Getting Started](../docs/getting-started/README.md)
- [Development Guide](../docs/getting-started/development.md)
- [Demo Guide](../docs/getting-started/demo-guide.md)
- [Local Development Guide](../docs/development/local-setup.md)
- [Operational Runbook](../docs/development/runbook.md)

### Architecture

- [System Context](../docs/architecture/system-context.md)
- [Containers](../docs/architecture/containers.md)
- [Trust Boundaries](../docs/architecture/trust-boundaries.md)

### Eventing and reliability

- [Outbox Pattern](../docs/eventing/outbox-pattern.md)
- [Kafka Topics](../docs/eventing/kafka-topics.md)
- [Concurrency and Consistency](../docs/reliability/concurrency-consistency.md)
- [Resilience Patterns](../docs/reliability/resilience-patterns.md)

### Observability

- [Telemetry](../docs/observability/telemetry.md)
- [Alerts and SLOs](../docs/observability/alerts-slos.md)
- [Observability Runbook](../docs/observability/runbook.md)

### Testing

- [Testing Strategy](../docs/testing/strategy.md)
- [Test Scenarios](../docs/testing/scenarios.md)
- [Load Testing](../docs/testing/load-testing.md)
- [Load Test Scripts](../load-tests/README.md)

---

## Quick Reference

```bash
# start the published stack
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d

# check status
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml ps

# follow logs
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml logs -f

# stop the stack
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down

# reset all local state
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down -v
```

Run the guided demo next: see the [Demo Guide](../docs/getting-started/demo-guide.md).

