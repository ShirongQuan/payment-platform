# Local Development Guide

## Table of Contents

- [Prerequisites](#prerequisites)
- [Start Dependencies](#start-dependencies)
- [Run Services](#run-services)
- [Test Commands](#test-commands)
- [Sample Requests](#sample-requests)
- [Troubleshooting](#troubleshooting)
- [Related Docs](#related-docs)

This guide covers local development for `payment-platform`:

- prerequisites
- how to start dependencies
- how to run each service
- test commands
- sample requests

## Prerequisites

- Java 21
- Maven 3.9+
- Docker Desktop (or Docker Engine + Docker Compose)
- `curl` for API checks

Optional (helpful):

- Postman or Bruno for API exploration
- pgAdmin (`http://localhost:5050`) and Kafka UI (`http://localhost:9091`)

Run the following commands from the repository root unless otherwise indicated.

## Start Dependencies

Start infra stack (Postgres, Kafka, Redis, Prometheus, Grafana, pgAdmin, Kafka UI):

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

Check container status:

```bash
docker compose -f infra/docker/docker-compose.yml ps
```

Notes:

- Postgres is exposed on `localhost:5432`.
- Kafka brokers are exposed on `localhost:9092`, `localhost:9094`, `localhost:9095`.
- Topic bootstrap is handled by `kafka-topic-init` (`auth.events`, `auth.events.ledger.dlt`).

Stop infra when done:

```bash
docker compose -f infra/docker/docker-compose.yml down
```

## Run Services

Open a separate terminal per service.

Authorisation calls fraud synchronously, so it's practical to start services in the
following order (this is a recommendation for a smoother local workflow, not a mandatory
startup dependency):

1. `fraud-service`
2. `ledger-service`
3. `auth-service`

### 1) Fraud Service

```bash
mvn -pl fraud-service spring-boot:run
```

- Base URL: `http://localhost:9010`

### 2) Ledger Service

```bash
mvn -pl ledger-service spring-boot:run
```

- Base URL: `http://localhost:9020`
- OpenAPI UI: `http://localhost:9020/swagger-ui.html`

### 3) Auth Service

```bash
mvn -pl auth-service spring-boot:run
```

- Base URL: `http://localhost:9000`
- OpenAPI UI: `http://localhost:9000/swagger-ui.html`

## Test Commands

Run all modules:

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

## Sample Requests

The examples below use shell variables so the commands are directly executable. Replace
the placeholder values with the IDs returned from the preceding responses.

### 1) Create account

```bash
curl -sS -X POST "http://localhost:9000/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currencyCode":"GBP"}'
```

Save the returned `id` as `ACCOUNT_ID`:

```bash
ACCOUNT_ID="<account-id-from-create-response>"
```

### 2) Deposit funds

```bash
curl -sS -X POST "http://localhost:9000/accounts/${ACCOUNT_ID}/deposits" \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"currencyCode":"GBP"}'
```

### 3) Create authorisation

```bash
curl -sS -X POST "http://localhost:9000/authorisations" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId":"'"${ACCOUNT_ID}"'",
    "idempotencyKey":"auth-001",
    "amount":10.00,
    "currencyCode":"GBP",
    "merchantReference":"order-123"
  }'
```

Save the returned `id` as `AUTHORISATION_ID`:

```bash
AUTHORISATION_ID="<id-from-authorisation-response>"
```

### 4) Capture authorisation

```bash
curl -sS -X POST "http://localhost:9000/authorisations/${AUTHORISATION_ID}/captures" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"capture-001"}'
```

### 5) Query ledger by authorisation

```bash
curl -sS "http://localhost:9020/authorisations/${AUTHORISATION_ID}"
```

Ledger projections are updated asynchronously through Kafka. If an event is not visible
immediately after capture, retry the query after a short interval and check the outbox
publisher and ledger consumer if the delay persists.

### 6) Query ledger account timeline

```bash
curl -sS "http://localhost:9020/accounts/${ACCOUNT_ID}/events"
```

### 7) Reverse an authorisation

A captured authorisation cannot subsequently be reversed under the current lifecycle
rules, so this example uses a separate, not-yet-captured authorisation.

Create a new authorisation to reverse:

```bash
curl -sS -X POST "http://localhost:9000/authorisations" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId":"'"${ACCOUNT_ID}"'",
    "idempotencyKey":"auth-002",
    "amount":10.00,
    "currencyCode":"GBP",
    "merchantReference":"order-124"
  }'
```

Save the returned `id` as `REVERSAL_AUTHORISATION_ID`:

```bash
REVERSAL_AUTHORISATION_ID="<id-from-authorisation-response>"
```

Reverse it:

```bash
curl -sS -X POST "http://localhost:9000/authorisations/${REVERSAL_AUTHORISATION_ID}/reversals" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"reverse-001","reasonCode":"CUSTOMER_REQUEST"}'
```

## Troubleshooting

- **Port already in use** (`9000`, `9010`, `9020`, `5432`, `9092`/`9094`/`9095`, `6379`, etc.): find
  and stop whatever is bound to the port, e.g. `lsof -i :9000`, then stop the conflicting process or
  `docker compose -f infra/docker/docker-compose.yml down` if it's a leftover container.
- **Unhealthy/exited containers**: run `docker compose -f infra/docker/docker-compose.yml ps` to
  spot unhealthy services, then `docker compose -f infra/docker/docker-compose.yml logs <service>`
  for details; `docker compose -f infra/docker/docker-compose.yml up -d` again to retry.
- **Auth-service can't reach fraud-service**: confirm `fraud-service` is running and healthy at
  `http://localhost:9010` first (see the recommended startup order above). Authorisation calls
  fraud synchronously; if fraud-service is unavailable, auth-service's resilience policy returns an
  unavailable fraud decision, and the authorisation outcome depends on the configured fallback policy.
- **Ledger query doesn't show a recent event**: this is expected immediately after capture, since
  ledger projections are updated asynchronously through Kafka (see note above). Retry after a short
  interval; if it persists, check `ledger-service` logs and the outbox/consumer troubleshooting
  steps in `docs/development/runbook.md`.

## Related Docs

- API details: `docs/api/auth-api.md`, `docs/api/ledger-api.md`
- Flows: `docs/flows/README.md`
- Data model: `docs/data/data-model.md`
- Operational debugging tips: `docs/development/runbook.md`

