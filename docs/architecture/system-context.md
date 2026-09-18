# System Context (C4 Level 1)

## Table of Contents

- [What the platform does](#what-the-platform-does)
- [External actors / systems](#external-actors--systems)
- [System boundary and responsibilities](#system-boundary-and-responsibilities)
- [Top 3 priorities for next step](#top-3-priorities-for-next-step)
- [Diagram](#diagram)

## What the platform does

The Payment Platform is a Spring Boot-based payment authorisation system that lets a client reserve funds
against an account (`authorise`), collect them (`capture`), or release them (`reverse`), while evaluating every
authorisation for fraud risk in real time, publishing durable domain events for every state change, and
projecting those events into an independent, queryable ledger — all with idempotent, concurrency-safe request
handling suitable for a payments-style workload.

## External actors / systems

Everything outside the Payment Platform's own deployment: the people/systems that call into it, and the people
who operate it. There is no external bank/card-network simulator in this MVP — authorisation decisions are made
entirely within the platform (fraud-service), not delegated to a simulated downstream processor.

| Actor / System | Type | Interaction |
|---|---|---|
| **Customer / Client App** | Person (via API caller) | Issues `authorise` / `capture` / `reverse` requests and reads account/authorisation state. In this MVP, "customer" traffic is simulated by Swagger UI, Postman, or the load-test scripts — there is no end-user front end. |
| **Admin / Ops** | Person | Operates the platform: inspects dashboards, traces, Kafka topics, and databases; runs manual recovery actions (e.g. outbox replay) per the [runbook](../development/runbook.md). |

## System boundary and responsibilities

At this zoom level the **Payment Platform** is a single box — a C4 System Context diagram intentionally does not
reveal internal services. Its internal composition (`auth-service`, `fraud-service`, `ledger-service`, Kafka,
Redis, PostgreSQL, and the observability stack) is detailed one level down in [Containers](./containers.md).

As one system, the platform is responsible for:

- exposing the account/authorisation/capture/reverse HTTP API
- enforcing idempotency and concurrency-safety on every mutating request (see
  [ADR 0005](../decisions/0005-idempotency-and-concurrency.md) /
  [ADR 0007](../decisions/0007-idempotency-store-and-key-policy.md))
- evaluating fraud risk before committing a reservation, with resilience (circuit breaker/timeout, see
  [ADR 0008](../decisions/0008-resilience4j-circuit-breaker-policy.md))
- durably publishing domain events (transactional outbox, see
  [ADR 0001](../decisions/0001-use-kafka-outbox.md)) and projecting them into an auditable ledger that exposes
  read-only authorisation/account-event history via its own query API
  ([ADR 0003](../decisions/0003-ledger-raw-and-normalized-events.md))

Everything needed to do the above — the three application services, messaging/data infrastructure, and the
observability stack — is part of one deployable platform today (single repo, single docker-compose environment,
no separate team/release boundary between them).

## Top 3 priorities for next step

1. **Add security.** Introduce Spring Security + JWT on the customer-facing API, with role separation so that
   operational/administrative endpoints (e.g. manual outbox replay, future reconciliation triggers) require an
   admin role while ordinary payment operations (`authorise`/`capture`/`reverse`) require an authenticated
   customer/client identity. This closes the biggest known gap called out in
   [trust-boundaries.md](./trust-boundaries.md) — today every endpoint is open with no authentication or
   authorization at all.
2. **Add reconciliation.** Introduce a scheduled reconciliation job/service that cross-checks
   `auth-service`'s authorisations, the outbox event log, and `ledger-service`'s projected entries, detects
   mismatches (e.g. an authorisation with no corresponding published event, or a ledger entry with no matching
   source event), and reports/persists the discrepancies for operator follow-up — turning today's implicit
   "these should always agree" assumption into an explicitly verified one.
3. **Add a customer-facing UI.** Today all "customer" interaction is simulated via Swagger UI, Postman, or
   load-test scripts; a minimal UI (account overview, authorise/capture/reverse actions, transaction history)
   would let the platform be demoed and exercised without an API client, and would be the first real external
   actor consuming the public API as intended.

## Diagram

Source: [`system-context.mmd`](./system-context.mmd) — open in a Mermaid-compatible viewer (the
Mermaid VS Code/IntelliJ plugin, or [mermaid.live](https://mermaid.live)) to render it.


