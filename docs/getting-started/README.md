# Getting Started

This guide is the main installation and execution reference for `payment-platform`. It covers
prerequisites, the fastest way to get the platform running, and every supported way to run it
locally, along with configuration, verification, and troubleshooting.

For a guided walkthrough of the payment flows once everything is running, see the
[Demo Guide](./demo-guide.md). For day-to-day development workflows (IDE setup, building,
testing, rebuilding images), see the [Development Guide](./development.md).

## Table of Contents

- [Prerequisites](#prerequisites)
- [Choose a Run Mode](#choose-a-run-mode)
- [Quick Start with Pre-Published Images](#quick-start-with-pre-published-images)
- [Run with Locally Built Images](#run-with-locally-built-images)
- [Run Services from an IDE](#run-services-from-an-ide)
- [Configuration](#configuration)
- [Verify the Platform](#verify-the-platform)
- [Stop and Clean Up](#stop-and-clean-up)
- [Troubleshooting](#troubleshooting)

## Prerequisites

Required for every run mode:

- **Docker Desktop** (or Docker Engine + Docker Compose v2) — runs Postgres, Redis, Kafka,
  Prometheus, Grafana, Tempo, and (in two of the three run modes) the application services
  themselves.
- **`curl`** — used throughout this guide and the demo for API checks.

Additional tools, depending on run mode:

| Tool | Needed for |
|---|---|
| Java 21 | Locally built images, IDE development |
| Maven 3.9+ | Locally built images, IDE development (the Docker build stage brings its own Maven, so host Maven is only required when building/running outside Docker) |
| An IDE with Spring Boot support (IntelliJ IDEA, VS Code, etc.) | IDE development |

Included at no extra setup cost — these web UIs are started automatically as part of the infra
Docker Compose stack, so there is nothing to install; just open the URL once the stack is up:

| UI | URL | Purpose |
|---|---|---|
| pgAdmin | `http://localhost:5050` (login `admin@example.com` / `admin`) | Browse Postgres schemas/tables without a CLI |
| Kafka UI | `http://localhost:9091` | Browse topics, partitions, and messages |
| RedisInsight | `http://localhost:5540` | Browse Redis keys (idempotency cache, fraud velocity counters) |
| Grafana | `http://localhost:3000` (login `admin` / `password` unless overridden) | Dashboards for metrics and traces |
| Prometheus | `http://localhost:9090` | Raw metrics/queries |
| Tempo (via Grafana Explore) | `http://localhost:3000` → Explore → Tempo datasource | Distributed traces |

Optional local tools you may want to install yourself:

- **Postman** or **Bruno** (or just `curl`) for exploring the APIs — not bundled, since they're
  general-purpose HTTP clients rather than part of the platform's infrastructure.

## Choose a Run Mode

| Mode | Best for | Application services | Infrastructure |
|---|---|---|---|
| [Pre-published images](#quick-start-with-pre-published-images) | Fastest demo | Docker Hub images | Docker Compose |
| [Locally built images](#run-with-locally-built-images) | Validating code changes | Local Docker builds | Docker Compose |
| [IDE development](#run-services-from-an-ide) | Debugging and development | IDE/local processes | Docker Compose |

All three modes share the same infrastructure stack
(`infra/docker/docker-compose.yml`: Postgres, Redis, Kafka, Prometheus, Grafana, Tempo, the OTel
Collector, and supporting UIs). They only differ in **how the three application services**
(`auth-service`, `fraud-service`, `ledger-service`) **are started** — as containers from published
images, as containers built from your local source, or as processes launched from your IDE/Maven.
The sections below describe only those differences; shared setup (prerequisites, configuration,
verification, cleanup) is documented once, further down this page.

## Quick Start with Pre-Published Images

The fastest way to see the whole platform running, using published `shirongquan/*` images from
Docker Hub — no build step required.

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

This starts the infra stack and all three application services in one command. Image references
default to `shirongquan/auth-service:1.0.0`, `shirongquan/fraud-service:1.0.0`, and
`shirongquan/ledger-service:1.0.0` (see `infra/docker/docker-compose.app.yml`), and can be
overridden per service, for example:

```bash
AUTH_SERVICE_IMAGE=shirongquan/auth-service:1.0.0 \
  docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

Once containers report healthy (see [Verify the Platform](#verify-the-platform)), jump straight to
the [Demo Guide](./demo-guide.md).

## Run with Locally Built Images

Use this mode to validate local code changes end-to-end as containers, exactly as they would run
in the pre-published-image mode, but built from your working copy.

1. Build images for the services you changed (each service has its own multi-stage `Dockerfile`
   that builds `shared-spring-lib` first, then the service itself):

   ```bash
   cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
   docker build -f auth-service/Dockerfile -t local/auth-service:dev .
   docker build -f fraud-service/Dockerfile -t local/fraud-service:dev .
   docker build -f ledger-service/Dockerfile -t local/ledger-service:dev .
   ```

2. Start the infra stack plus the application overlay, pointing each `*_SERVICE_IMAGE` variable at
   your local image tag:

   ```bash
   AUTH_SERVICE_IMAGE=local/auth-service:dev \
   FRAUD_SERVICE_IMAGE=local/fraud-service:dev \
   LEDGER_SERVICE_IMAGE=local/ledger-service:dev \
     docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
   ```

   You only need to override the images you actually rebuilt; the rest fall back to the
   pre-published defaults.

3. After changing code, rebuild the affected image and recreate just that container:

   ```bash
   docker build -f auth-service/Dockerfile -t local/auth-service:dev .
   AUTH_SERVICE_IMAGE=local/auth-service:dev \
     docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d --no-deps auth-service
   ```

See [Development Guide § Build Docker Images](./development.md#build-docker-images) for a
build-loop oriented walkthrough of this same mode.

## Run Services from an IDE

Use this mode for active development and debugging: infrastructure runs in Docker, but the three
Spring Boot services run as local JVM processes you can attach a debugger to.

1. Start only the infra stack (no application containers):

   ```bash
   cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
   docker compose -f infra/docker/docker-compose.yml up -d
   ```

2. Run each service from the repository root, in its own terminal (or as an IDE run/debug
   configuration targeting the same Maven goal):

   ```bash
   mvn -pl auth-service spring-boot:run
   mvn -pl fraud-service spring-boot:run
   mvn -pl ledger-service spring-boot:run
   ```

   Default local ports: `auth-service` → `9000`, `fraud-service` → `9010`, `ledger-service` →
   `9020`. Each service's `application.yml` defaults its Kafka/Postgres/Redis/OTel connection
   properties to `localhost`, matching the ports the infra Compose file publishes to the host — no
   extra configuration is needed for this mode.

See [Development Guide § IDE Setup](./development.md#ide-setup) for IDE-specific run/debug
configuration tips.

## Configuration

Configuration is layered: each service ships sensible `localhost` defaults in its
`application.yml`, which the Docker Compose app overlay (`infra/docker/docker-compose.app.yml`)
overrides with container-network hostnames via environment variables.

Key environment variables (all optional — shown with their defaults):

| Variable | Default | Purpose |
|---|---|---|
| `AUTH_SERVICE_IMAGE` / `FRAUD_SERVICE_IMAGE` / `LEDGER_SERVICE_IMAGE` | `shirongquan/<service>:1.0.0` | Which image to run for each service (Compose modes only) |
| `AUTH_SERVICE_PORT` / `FRAUD_SERVICE_PORT` / `LEDGER_SERVICE_PORT` | `9000` / `9010` / `9020` | Host+container port and `SERVER_PORT` for each service |
| `FRAUD_SERVICE_PROFILE` | `dev` | Set to an empty/prod-like value to disable the dev-only `/internal/test/failure-mode` chaos endpoint on fraud-service |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/<db>` / `postgres` / `postgres` | Postgres connection (per-service database: `auth_db`, `fraud_db`, `ledger_db`) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_DATABASE` | `localhost` / `6379` / `0` | Redis connection (auth-service and fraud-service only) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers (auth-service and ledger-service) |
| `FRAUD_BASE_URL` | `http://localhost:9010` | Where auth-service calls fraud-service's synchronous `/fraud/check` |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP HTTP endpoint for the OTel Collector |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Fraction of root traces sampled (keep `1.0` locally for full visibility) |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `password` | Grafana login (infra stack) |

For IDE development, no overrides are needed — the `localhost` defaults already line up with the
ports the infra Compose file publishes to the host. For the two container-based modes, the app
overlay (`infra/docker/docker-compose.app.yml`) already sets the container-network values above;
you only need to export overrides when you want to change a port, an image tag, or a credential.

## Verify the Platform

Check container health (works whichever Compose files you used):

```bash
docker compose -f infra/docker/docker-compose.yml ps
```

If application containers are running, add the app overlay file to the same `ps`/`logs` commands.

Check each service's health endpoint:

```bash
curl -s http://localhost:9000/actuator/health | jq
curl -s http://localhost:9010/actuator/health | jq
curl -s http://localhost:9020/actuator/health | jq
```

Each should report `"status":"UP"`. Then confirm the platform accepts a request end-to-end:

```bash
curl -sS -X POST "http://localhost:9000/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currencyCode":"GBP"}'
```

A `200 OK` with a new `accountId` confirms auth-service, Postgres, and (implicitly) migrations are
all working. For the full authorise/capture/reverse walkthrough, continue to the
[Demo Guide](./demo-guide.md).

Also useful:

- Kafka UI: `http://localhost:9091` — confirms the `auth.events` / `auth.events.ledger.dlt` topics
  exist (created automatically by the `kafka-topic-init` container).
- pgAdmin: `http://localhost:5050` (login `admin@example.com` / `admin`) — confirms `auth_db`,
  `fraud_db`, `ledger_db` schemas are present.
- Grafana: `http://localhost:3000` (login `admin` / `password` unless overridden) — confirms the
  `Payment Platform Overview` dashboard is provisioned and scraping data.

Want to see what "working" looks like before you dig in yourself? See
[`docs/observability/telemetry.md` § Dashboards](../observability/telemetry.md#5-dashboards) and
[`docs/observability/dashboard-screenshots/`](../observability/dashboard-screenshots/) for sample
dashboard panels, and
[`docs/observability/telemetry.md` § Correlating Logs, Metrics, and Traces](../observability/telemetry.md#6-correlating-logs-metrics-and-traces)
for a worked example matching a log line to its trace and metric data point — also demonstrated live
in the [Demo Guide § Correlate a Log Line to a Trace](./demo-guide.md#correlate-a-log-line-to-a-trace).

## Stop and Clean Up

Stop containers but keep data volumes (Postgres data, Grafana state, etc.):

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down
```

Stop containers and remove all volumes (full reset — next start runs migrations from scratch):

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down -v
```

If you only started the infra stack (IDE development mode), omit the app overlay file from the
commands above, and stop your IDE-launched service processes directly.

## Troubleshooting

**A container is stuck "starting" / not healthy**

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml logs -f <service-name>
```

Most services (Kafka brokers, Postgres, application services) declare Docker healthchecks and
`depends_on: condition: service_healthy`, so a slow machine simply needs more time before
downstream services start — check `docker compose ps` again after a minute.

**Application container can't reach Postgres/Redis/Kafka**

Confirm you started the infra stack (`docker-compose.yml`) together with the app overlay
(`docker-compose.app.yml`) in the *same* `docker compose` invocation — the app overlay's
`depends_on` blocks wait for the infra containers, but only if both files are supplied together.

**Port already in use**

Another local process (or a previous, not-fully-stopped stack) is bound to one of the platform's
ports (`9000`, `9010`, `9020`, `5432`, `6379`, `9092`/`9094`/`9095`, `3000`, `9090`, `3200`, ...).
Stop the conflicting process, or override the corresponding `*_SERVICE_PORT` variable (application
services only) and adjust your requests accordingly.

**IDE-run service can't reach Docker-network hostnames**

If you see connection errors to hostnames like `postgres`, `redis`, or `kafka-1`, you likely copied
an environment variable meant for the Compose app overlay. IDE-run services should use the
`localhost`-based defaults already baked into each `application.yml` — remove any
`SPRING_DATASOURCE_URL` / `SPRING_KAFKA_BOOTSTRAP_SERVERS` / etc. overrides that point at container
hostnames.

**Kafka topics missing / ledger-service not receiving events**

Confirm the one-shot `kafka-topic-init` container completed successfully:

```bash
docker compose -f infra/docker/docker-compose.yml logs kafka-topic-init
```

It creates `auth.events` and `auth.events.ledger.dlt` (6 partitions, replication factor 3) and
exits; application services wait on `service_completed_successfully` for this container before
starting.

**Need more targeted diagnostics?**

See [`docs/development/runbook.md`](../development/runbook.md) for deeper Postgres/Redis/Kafka/Tempo
inspection commands, and [`docs/observability/runbook.md`](../observability/runbook.md) for
signal-driven failure triage.
