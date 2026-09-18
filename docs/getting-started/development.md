# Development Guide

This guide covers the day-to-day developer workflow for `payment-platform`: repository/module
structure, IDE setup, running individual services, building the project, executing tests,
database migrations, rebuilding images, and troubleshooting local development issues.

For installation and the different ways to *run* the whole platform, see the
[Getting Started guide](./README.md). For the detailed *design* of the test suite — the test
pyramid, scope of each level, and the critical scenario catalog — see [`docs/testing/`](../testing/);
this page only explains how to execute those tests.

## Table of Contents

- [Project Structure](#project-structure)
- [Build the Project](#build-the-project)
- [IDE Setup](#ide-setup)
- [Run Services Locally](#run-services-locally)
- [Database Migrations](#database-migrations)
- [Run Tests](#run-tests)
- [Formatting and Static Analysis](#formatting-and-static-analysis)
- [Build Docker Images](#build-docker-images)
- [Development Workflow](#development-workflow)
- [Troubleshooting](#troubleshooting)

## Project Structure

`payment-platform` is a multi-module Maven reactor (parent `pom.xml`, packaging `pom`):

```text
payment-platform/
├── pom.xml                  # parent POM: modules, dependency management, Java 21
├── auth-service/            # authorisation, capture, reverse, outbox publisher
│   ├── Dockerfile
│   └── src/main/java, src/main/resources, src/test/java
├── fraud-service/           # synchronous fraud/risk decisioning API
│   ├── Dockerfile
│   └── src/...
├── ledger-service/          # Kafka consumer + read-only ledger projection API
│   ├── Dockerfile
│   └── src/...
├── shared-spring-lib/       # cross-cutting library shared by all three services
│   └── src/...
├── infra/                   # docker-compose files, Grafana/Prometheus/Tempo/Kafka config
├── load-tests/              # bash traffic-generation scripts
└── docs/                    # this documentation
```

Each service module is an independent, deployable Spring Boot application with its own
`application.yml`, Flyway migrations, and Dockerfile. `shared-spring-lib` is a plain library module
(published only to the local Maven repository, not to a remote registry) that provides common
cross-cutting concerns — see
[ADR 0009: Shared Spring Lib for Cross-Cutting Concerns](../decisions/0009-shared-spring-lib-for-cross-cutting.md)
for the rationale.

## Build the Project

Build every module (compiles, runs tests by default, installs `shared-spring-lib` locally so the
other modules can depend on it):

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn clean install
```

Build without running tests (fastest inner loop after a shared-lib change):

```bash
mvn -DskipTests clean install
```

Build a single module (Maven resolves `shared-spring-lib` from your local repository, so install it
at least once first if you haven't already):

```bash
mvn -pl auth-service -am clean package
```

`-am` ("also make") ensures any changed dependency modules (like `shared-spring-lib`) are rebuilt
first.

## IDE Setup

1. Import the repository as an existing Maven project (`pom.xml` at the repository root) — most
   IDEs (IntelliJ IDEA, VS Code + Java extensions) detect the multi-module reactor automatically.
2. Set the project SDK/language level to **Java 21**.
3. Let the IDE resolve dependencies via Maven; if `auth-service`/`fraud-service`/`ledger-service`
   show unresolved `org.example:shared-spring-lib` dependencies, run `mvn -pl shared-spring-lib
   install` once to publish it to your local `~/.m2` repository.
4. Start the infra stack before running/debugging any service from the IDE:

   ```bash
   docker compose -f infra/docker/docker-compose.yml up -d
   ```

5. Create a Run/Debug configuration per service using the Spring Boot main class (or the
   equivalent `mvn -pl <service> spring-boot:run` Maven goal), so you can set breakpoints and
   inspect state while the infra dependencies run in Docker. See
   [Getting Started § Run Services from an IDE](./README.md#run-services-from-an-ide) for the
   corresponding run-mode overview.
6. Recommended: enable annotation processing for MapStruct (used in `ledger-service`) and Lombok
   if the IDE doesn't already do so automatically for Maven-based projects.

## Run Services Locally

Open a separate terminal (or IDE run configuration) per service, from the repository root:

```bash
mvn -pl auth-service spring-boot:run
mvn -pl fraud-service spring-boot:run
mvn -pl ledger-service spring-boot:run
```

| Service        | Base URL                | OpenAPI UI                              |
|----------------|-------------------------|-----------------------------------------|
| auth-service   | `http://localhost:9000` | `http://localhost:9000/swagger-ui.html` |
| fraud-service  | `http://localhost:9010` | `http://localhost:9010/swagger-ui.html` |
| ledger-service | `http://localhost:9020` | `http://localhost:9020/swagger-ui.html` |

The infra stack (`docker compose -f infra/docker/docker-compose.yml up -d`) must already be
running — see [Getting Started § Run Services from an IDE](./README.md#run-services-from-an-ide).

## Database Migrations

Each service owns its own Postgres schema and manages it with **Flyway**, applied automatically on
startup (`spring.jpa.hibernate.ddl-auto: validate` — Hibernate never auto-generates schema; Flyway
is the single source of truth):

- `auth-service/src/main/resources/db/migration/` — `V1__init.sql`,
  `V2__seed_initial_test_data.sql`, `V3__add_outbox_trace_parent.sql`
- `fraud-service/src/main/resources/db/migration/`
- `ledger-service/src/main/resources/db/migration/`

To add a migration: create the next `V<N>__description.sql` file in the relevant service's
migration folder and restart the service (or re-run its tests) — Flyway detects and applies new,
unapplied versions automatically. Never edit an already-applied migration file; add a new one
instead, so Flyway's checksum validation doesn't fail for anyone who already ran the old version.

To reset a database from scratch locally, stop the stack and drop its volume:

```bash
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

## Run Tests

This section covers *how to run* the test suite. For test design — the pyramid, what each level is
responsible for, mock/stub strategy, and the required-coverage scenario catalog — see
[`docs/testing/strategy.md`](../testing/strategy.md) and
[`docs/testing/scenarios.md`](../testing/scenarios.md).

Run all unit + integration tests across every module:

```bash
mvn test
```

Run tests for a single module:

```bash
mvn -pl auth-service test
mvn -pl fraud-service test
mvn -pl ledger-service test
```

Run one test class:

```bash
mvn -pl auth-service -Dtest=AuthorisationServiceImplTest test
```

Run the full verification lifecycle (what CI runs — see `.github/workflows/ci.yml`), including any
Testcontainers-backed integration tests bound to `verify`:

```bash
mvn -B -ntp clean verify
```

Integration tests that use `spring-boot-testcontainers` / `org.testcontainers:junit-jupiter`
require a working Docker daemon on the machine running the tests — start Docker Desktop (or your
Docker Engine) first if those tests fail to start containers.

### End-to-end and load/traffic scenarios

End-to-end journeys and reliability signals (idempotency replay, circuit breaker transitions, DLT
routing, concurrency conflicts) are exercised against a fully running stack using the scripts in
`load-tests/`, documented in [`docs/testing/load-testing.md`](../testing/load-testing.md) and used
directly in the [Demo Guide](./demo-guide.md).

```bash
cd load-tests
./generate-traffic.sh
```

## Formatting and Static Analysis

The project does not currently enforce a code formatter or static analysis tool (no
Checkstyle/Spotless/PMD plugin is configured in any `pom.xml` today). Until one is added:

- Keep formatting consistent with the surrounding file (most of the codebase follows standard
  Java/Spring conventions with 4-space indentation).
- Rely on your IDE's default Java formatter and its built-in inspections for quick feedback.
- Adding a shared formatter (e.g. Spotless with Google Java Format) and a Checkstyle/PMD ruleset
  enforced in CI is tracked as a future improvement — see [`docs/roadmap.md`](../roadmap.md).

## Build Docker Images

Each service has its own multi-stage `Dockerfile` (Maven build stage → `eclipse-temurin:21-jre`
runtime stage) that first installs `shared-spring-lib` into the build container's local Maven
repository, then builds the service itself. Build from the **repository root** so the Dockerfile
can see both the parent POM and `shared-spring-lib`'s sources:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
docker build -f auth-service/Dockerfile -t local/auth-service:dev .
docker build -f fraud-service/Dockerfile -t local/fraud-service:dev .
docker build -f ledger-service/Dockerfile -t local/ledger-service:dev .
```

Run the freshly built image(s) alongside the infra stack by pointing the app overlay at your local
tags — see
[Getting Started § Run with Locally Built Images](./README.md#run-with-locally-built-images) for
the full up/rebuild/recreate loop.

## Development Workflow

A typical inner loop for a code change:

1. Start the infra stack once: `docker compose -f infra/docker/docker-compose.yml up -d`.
2. Run the service(s) you're changing from the IDE or `mvn spring-boot:run` (fast reload, easy
   debugging).
3. Run focused unit/integration tests for the module you're touching (`mvn -pl <service> test`).
4. Exercise the change manually with `curl`/Postman/Bruno, or the sample requests in
   [`docs/development/local-setup.md`](../development/local-setup.md).
5. Before pushing, run the full verification build (`mvn -B -ntp clean verify`) to mirror CI.
6. If the change affects a service that others run as a container (demo/E2E), rebuild its Docker
   image and re-validate with the containerized run mode before opening a PR.

## Troubleshooting

**`mvn -pl <service>` fails with "cannot find symbol" from `shared-spring-lib`**

Install `shared-spring-lib` to your local Maven repository first:

```bash
mvn -pl shared-spring-lib install
```

Or build with `-am` so Maven rebuilds it automatically: `mvn -pl auth-service -am test`.

**Testcontainers-backed integration tests hang or fail to start**

Confirm Docker Desktop/Engine is running and reachable (`docker info`). Testcontainers needs a live
Docker daemon even though the surrounding stack isn't otherwise involved.

**Flyway migration checksum mismatch**

You (or a teammate) likely edited an already-applied migration file instead of adding a new one.
Revert the edit and add a new `V<N>__description.sql` migration, or, for a local-only database, drop
and recreate the Postgres volume (`docker compose -f infra/docker/docker-compose.yml down -v`) to
reset the applied-migrations history.

**IDE can't resolve `org.example:shared-spring-lib`**

Run `mvn -pl shared-spring-lib install` once, then re-import/refresh the Maven project in your IDE.

**Service running from the IDE can't reach Postgres/Redis/Kafka**

Confirm the infra stack is up (`docker compose -f infra/docker/docker-compose.yml ps`) and that you
haven't set container-network environment variables (e.g. `SPRING_DATASOURCE_URL` pointing at
`postgres` instead of `localhost`) meant for the Docker Compose app overlay — see
[Getting Started § Troubleshooting](./README.md#troubleshooting).

**More operational/debugging commands**

See [`docs/development/runbook.md`](../development/runbook.md) for Postgres/Redis CLI inspection,
Tempo TraceQL queries, and Kafka DLT-generation commands used during development.
