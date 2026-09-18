# API Overview

## Table of Contents

- [Purpose](#purpose)
- [Services](#services)
- [Common conventions](#common-conventions)
- [Security](#security)
- [Links](#links)

## Purpose

Cross-service API conventions for the payment platform.

## Services

- **Auth API** (`auth-service`, port `9000`): account management, authorisation (reserve), capture,
  and reversal. Publishes domain events to Kafka topic `auth.events` for downstream projection.
- **Fraud API** (`fraud-service`, port `9010`): synchronous risk evaluation, called by auth-service
  before an authorisation reserves funds. Not exposed to external callers.
- **Ledger API** (`ledger-service`, port `9020`): read-only query/projection service. Consumes
  auth-service's Kafka events and exposes them as authorisation and account-event timelines; it does
  not accept any posting/write requests itself.

None of the services are mounted behind a `context-path` — all endpoints are relative to the service
root (e.g. `http://localhost:9000/accounts`), not `/api/v1/...`.

## Common conventions

- No global base path/version prefix is currently applied; each service's paths are documented in its
  own API doc ([Auth API](./auth-api.md), [Fraud API](./fraud-api.md), [Ledger API](./ledger-api.md)).
- JSON field naming: `camelCase`; timestamps are `OffsetDateTime`/ISO-8601 with a UTC offset
  (e.g. `2026-07-31T10:00:00Z`).
- Correlation: a correlation id is resolved/generated per request (see `CorrelationIdResolver` in
  `shared-spring-lib`) and included in log output (`%X{correlationId}`); OpenTelemetry trace/span ids
  are also propagated via standard `traceparent` headers and included in logs.
- Error envelope: RFC 7807 `application/problem+json` (`ProblemDetail`), with a service-specific
  `errorCode` field and, where relevant, additional context fields (e.g. `accountId`,
  `authorisationId`, `currencyCode`). See each service's exception handler
  (`AuthExceptionHandler`, `FraudExceptionHandler`, and ledger-service's equivalent).
- No pagination/sorting/filtering is implemented anywhere; all list-returning endpoints
  (e.g. ledger's authorisation-events lookup) return the full result set.
- Standard HTTP status usage: `200` success, `400` validation, `404` not found, `409` conflict
  (idempotency/state conflicts), `500` unexpected errors.

## Security

- Application-level authentication/authorization (Spring Security + JWT, customer vs. admin role separation)
  is planned — see [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized). Today, each service is
  reachable directly on its configured port with no gateway in front of them; an API gateway is also planned
  (see [roadmap Next Steps #4](../roadmap.md#5-next-steps-prioritized)). See
  [Trust Boundaries](../architecture/trust-boundaries.md) for the full picture.
- Services call each other over plain HTTP (e.g. auth-service → fraud-service via
  `fraud.base-url`, default `http://localhost:9010`).

## Links

- [Idempotency](./idempotency.md)
- [Auth API](./auth-api.md)
- [Fraud API](./fraud-api.md)
- [Ledger API](./ledger-api.md)

