# API Docs Index

## Table of Contents

- [Purpose](#purpose)
- [Services](#services)
- [Common conventions](#common-conventions)
- [Security](#security)
- [Idempotency](#idempotency)
    - [Global policy](#global-policy)
    - [Request lifecycle (auth-service)](#request-lifecycle-auth-service)
    - [Response behaviour](#response-behaviour)
    - [Service notes](#service-notes)
    - [Failure cases](#failure-cases)
- [Service docs](#service-docs)
- [OpenAPI specs](#openapi-specs)

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

Service endpoints are exposed relative to their respective application roots (for example,
`http://localhost:9000/accounts`), without a shared `context-path` or `/api/v1` prefix. The
[roadmap](../roadmap.md#4-next-steps-prioritised) explores introducing versioned routing through an
API gateway while preserving the existing service-level endpoint paths.

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
- Collection endpoints currently return complete result sets without pagination, sorting, or
  filtering parameters (for example, the ledger authorisation-events lookup).
- Standard HTTP status usage: `200` success, `400` validation, `404` not found, `409` conflict
  (idempotency/state conflicts), `500` unexpected errors.

## Security

- The current service interfaces are directly accessible on their configured ports; application-level
  authentication and authorisation are not enforced by these endpoints. See
  [Trust Boundaries](../architecture/trust-boundaries.md) for the documented security boundaries.
- Services communicate over HTTP (for example, auth-service → fraud-service via `fraud.base-url`,
  default `http://localhost:9010`).
- The [roadmap](../roadmap.md#4-next-steps-prioritised) explores Spring Security, JWT-based customer/admin
  role separation, and gateway-based API routing as future architectural extensions.

## Idempotency

Prevent duplicate financial side effects for retried requests (reserve, capture, reverse, and fraud
evaluation), even under client retries, concurrent races, and at-least-once Kafka delivery.

See also [ADR 0005: Idempotency and Concurrency Rules](../decisions/0005-idempotency-and-concurrency.md)
for the full design rationale.

### Global policy

- No `Idempotency-Key` HTTP header is used. Instead, `idempotencyKey` is a required **field in the
  request body** for `POST /authorisations`, `POST /authorisations/{id}/captures`,
  `POST /authorisations/{id}/reversals` (auth-service), and `POST /fraud/check` (fraud-service).
- Scope is **operation-scoped, not global/merchant-scoped**:
    - `authorise`: `accountId + idempotencyKey` (persisted uniqueness:
      `account_id + event_type(AUTHORISED/DECLINED) + idempotency_key`)
    - `capture` / `reverse`: `authorisationId + idempotencyKey` (persisted uniqueness:
      `account_id + event_type(CAPTURED/REVERSED) + idempotency_key`)
    - fraud `check`: `accountId + idempotencyKey` (DB unique constraint on the evaluation row)
- Payload binding: the request's semantic fields are hashed into a fingerprint (SHA-256 of a
  canonical join of the relevant fields, e.g. `accountId+amount+currencyCode+merchantReference` for
  authorise). Same key + different fingerprint => `409 IDEMPOTENCY_CONFLICT`.
- Response cache: auth-service caches the finalised response per `(operationType, scopeId,
  idempotencyKey)` in Redis with a **24 hour TTL** (`idempotency.ttl-hours`), in addition to the
  durable uniqueness constraint on the `authorisation_event` table.

### Request lifecycle (auth-service)

1. Receive request + `idempotencyKey`.
2. Check the Redis response cache for `(operationType, scopeId, idempotencyKey)`; if present and the
   fingerprint matches, replay the cached response immediately (no DB write).
3. Otherwise execute the operation inside a DB transaction (reserve/capture/reverse), relying on the
   unique index `uq_authorisation_event_account_eventtype_idempotency` as the final race guard.
4. On success, persist the domain event and store the response in the Redis cache for future replays.
5. On a unique-constraint violation (`ConcurrentIdempotencyRaceException`, i.e. a concurrent request
   with the same key won the race), re-read the committed event/entity and resolve the response from
   it instead of failing the request — matching key ⇒ replay; non-matching key ⇒ conflict/state error.

### Response behaviour

- Same key + same payload + already completed ⇒ replay original response with `200 OK` (no new side
  effect).
- Same key + same payload + a concurrent request is still committing ⇒ resolved via the
  race-recovery path above once the winner commits (not a distinct HTTP status; the loser waits on the
  DB transaction and then either replays or conflicts).
- Same key + different payload ⇒ `409 Conflict` (`IDEMPOTENCY_CONFLICT`).
- Different key targeting an authorisation that already completed a terminal transition (e.g. capture
  retried with a new key after the authorisation is already `CAPTURED`) ⇒ `409 Conflict`
  (`INVALID_AUTHORISATION_STATE` / `AuthorisationIllegalStateException`).
- fraud-service additionally distinguishes "still being scored": a duplicate key while the original
  evaluation row is still `PENDING` ⇒ `409 FRAUD_EVALUATION_IN_PROGRESS`.

### Service notes

- **Auth API**: `idempotencyKey` is required on authorise/capture/reverse (see
  [Auth API](./auth-api.md)). Account creation and deposit requests are processed as separate operations
  on each successful submission; replaying either POST can create another account or apply another deposit.
- **Fraud API**: `idempotencyKey` required on `POST /fraud/check` (see [Fraud API](./fraud-api.md)),
  scoped by `accountId`; reads have no idempotency concept since there are none.
- **Ledger API**: fully read-only; no posting endpoints exist, so there is nothing to key on the
  request side. Deduplication instead happens on the **consumer side** for inbound Kafka events (see
  below).

### Failure cases

- Client times out after the server has committed but before the response is delivered: safe, the
  retry replays via the Redis cache or the durable unique-constraint/event lookup.
- Consumer duplication from outbox/Kafka (at-least-once delivery): ledger-service records each
  consumed `eventId` in a `processed_event` table using `INSERT ... ON CONFLICT DO NOTHING`; the first
  insert processes the event, duplicate `eventId` deliveries are silently ignored. This gives
  effectively-once projection without distributed transactions.
- Partial downstream failure (e.g. the database commit succeeds but Kafka publication fails):
  the committed outbox record remains available for publication retries. The auth-service Kafka
  producer uses `acks=all` and `enable.idempotence=true`; the outbox claim lease coordinates
  publication attempts. Because delivery is at least once, repeated publications remain possible,
  and ledger-service deduplicates by `eventId` during projection.

## Service docs

- [Auth API](./auth-api.md)
- [Fraud API](./fraud-api.md)
- [Ledger API](./ledger-api.md)

## OpenAPI specs

- [`openapi/auth.yaml`](./openapi/auth.yaml)
- [`openapi/fraud.yaml`](./openapi/fraud.yaml)
- [`openapi/ledger.yaml`](./openapi/ledger.yaml)


