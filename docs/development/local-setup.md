# Local Development Guide

## Table of Contents

- [Prerequisites](#prerequisites)
- [Start Dependencies](#start-dependencies)
- [Run Services](#run-services)
- [Test Commands](#test-commands)
- [Sample Requests](#sample-requests)
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

## Start Dependencies

From repository root:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
```

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

### 1) Auth Service

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn -pl auth-service spring-boot:run
```

- Base URL: `http://localhost:9000`
- OpenAPI UI: `http://localhost:9000/swagger-ui.html`

### 2) Fraud Service

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn -pl fraud-service spring-boot:run
```

- Base URL: `http://localhost:9010`

### 3) Ledger Service

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn -pl ledger-service spring-boot:run
```

- Base URL: `http://localhost:9020`
- OpenAPI UI: `http://localhost:9020/swagger-ui.html`

## Test Commands

Run all modules:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn test
```

Run tests for a single module:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn -pl auth-service test
mvn -pl fraud-service test
mvn -pl ledger-service test
```

Run one test class:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
mvn -pl auth-service -Dtest=AuthorisationServiceImplTest test
```

## Sample Requests

### 1) Create account

```bash
curl -sS -X POST "http://localhost:9000/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currencyCode":"GBP"}'
```

Save `accountId` from the response for the next steps.

### 2) Deposit funds

```bash
curl -sS -X POST "http://localhost:9000/accounts/{accountId}/deposits" \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"currencyCode":"GBP"}'
```

### 3) Create authorisation

```bash
curl -sS -X POST "http://localhost:9000/authorisations" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId":"{accountId}",
    "idempotencyKey":"auth-001",
    "amount":10.00,
    "currencyCode":"GBP",
    "merchantReference":"order-123"
  }'
```

Save returned `id` as `authorisationId`.

### 4) Capture authorisation

```bash
curl -sS -X POST "http://localhost:9000/authorisations/{authorisationId}/captures" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"capture-001"}'
```

### 5) Query ledger by authorisation

```bash
curl -sS "http://localhost:9020/authorisations/{authorisationId}"
```

### 6) Query ledger account timeline

```bash
curl -sS "http://localhost:9020/accounts/{accountId}/events"
```

## Related Docs

- API details: `docs/api/auth-api.md`, `docs/api/ledger-api.md`
- Flows: `docs/flows/README.md`
- Data model: `docs/data/data-model.md`
- Operational debugging tips: `docs/development/runbook.md`

