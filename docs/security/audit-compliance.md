# Audit & Compliance

## Table of Contents

- [Audit Events to Capture](#audit-events-to-capture)
- [Required Event Fields](#required-event-fields)
- [Log Integrity/Tamper Considerations](#log-integritytamper-considerations)
- [Compliance Posture Statement](#compliance-posture-statement)
- [Next Steps + Roadmap Milestones](#next-steps--roadmap-milestones)
- [Roadmap](#roadmap)

> **Reading this document:** every section describes **what's implemented today** and the
> **next steps** planned to extend it further, per the [roadmap](../roadmap.md). This is a
> working MVP — a formal audit/compliance program is planned as future scope,
> and the next-steps lists below reflect deliberate sequencing rather than oversights.

## Audit Events to Capture

### Current

`auth-service` maintains an **append-only audit trail** for every authorisation lifecycle
transition in the `authorisation_event` table (`AuthorisationEventEntity`):

- `AUTHORISATION_AUTHORISED`, `AUTHORISATION_DECLINED`, `AUTHORISATION_CAPTURED`,
  `AUTHORISATION_REVERSED` — one row per state transition, never updated after creation.
- The fraud-driven **account auto-lock** side effect is captured on the `account` row's `status`
  column (flips to `LOCKED`); emitting a distinct audit event for the lock transition itself is a
  planned enhancement — see [Next step](#next-step) below.
- `ledger-service` maintains a parallel, downstream **event log** (`ledger_event_log`) projected
  from the same Kafka events, giving a second, consumer-side copy of the same audit trail.
- `fraud-service` persists one row per fraud check in `fraud_evaluation` (decision, risk score, IP
  address, reasons), which functions as the audit record for the fraud-check "auth attempt".

### Next step

- Emit a structured audit event for the account-lock side effect (currently only visible via the
  `account.status` column, not a first-class event) — see
  [Failure Scenarios § 7](../flows/failure-scenarios.md#7-fraud-decline-and-account-auto-lock).
- Introduce admin-only endpoints (e.g. account unlock, replay triggers) with structured audit
  events once an admin surface exists — today, outbox replay and DLT reprocessing are manual,
  operator-driven SQL actions (see
  [Outbox Backlog Recovery](../flows/outbox-backlog-recovery.md)), relying on database change
  history and operator discipline rather than a structured audit event.
- Once authentication (Spring Security + JWT) lands
  ([roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized)), introduce auth-attempt and
  admin-action audit events (login success/failure, role-gated action performed) — every request
  is currently anonymous at the application layer, so there is no login/auth-failure event to
  capture yet.
- Add a config-change audit trail once any admin-configurable runtime settings exist (all
  configuration is currently static, deployment-time `application.yml`/environment variables).

## Required Event Fields

### Current

Every `authorisation_event` row captures:

| Field | Present today | Notes |
|-------|-----------------|-------|
| **Action / event type** | ✅ `event_type` (`AUTHORISED`/`DECLINED`/`CAPTURED`/`REVERSED`) | |
| **Entity** | ✅ `authorisation_id`, `account_id` | |
| **Timestamp** | ✅ `created_at` | |
| **Correlation id** | ✅ `correlation_id` | Ties the event back to the originating HTTP request and, via Kafka headers/`traceparent`, to the full distributed trace |
| **Reason / context** | ✅ `reason_code` (e.g. `INSUFFICIENT_FUNDS`, `FRAUD_DECLINED`, `CUSTOMER_REQUEST`) | |
| **Amount / currency** | ✅ `amount`, `currency_code` | Financial "before/after" is reconstructable from the sequence of events per authorisation (e.g. `AUTHORISED` → `CAPTURED` implies reserved-balance debited) rather than an explicit before/after snapshot column |
| **Actor** | ➖ **planned (future scope)** | Authenticated caller identity is planned as part of upcoming auth work — every event is currently attributed to "the system", since the auth layer is planned but not yet built |
| **Explicit before/after value snapshot** | ➖ **derived, not stored** | The event log is transition-based (event type implies the state change) rather than storing explicit before/after balance snapshots on each row — state is deterministically reconstructable from the full event sequence |

### Next step

- Add an `actor` field (populated from the JWT subject/service-account identity once
  authentication lands) to `authorisation_event` and any future admin-action audit table — see
  [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized). This is the top-priority
  addition for attributing events to a specific authenticated caller.
- Consider adding explicit before/after balance snapshots to `authorisation_event` if a stricter
  audit/compliance standard requires it (currently deferred since the event-type + amount is
  sufficient to reconstruct state deterministically given the full event sequence).

## Log Integrity/Tamper Considerations

### Current

- Structured logs across all three services share a common pattern including `traceId`, `spanId`,
  and `correlationId` (`CorrelationIdFilter` in `shared-spring-lib`), giving a consistent,
  cross-service audit trail for request tracing.
- The `authorisation_event` table is **application-level append-only** — no `UPDATE`/`DELETE` code
  path exists against it in the codebase; rows are only ever inserted.
- Distributed traces (OpenTelemetry → Tempo) provide an independent, out-of-band corroboration of
  the request flow, correlated by the same `traceparent`/`correlationId`, which raises the bar for
  tampering with the primary log/DB record alone.

### Next step

- Add cryptographic tamper-evidence (hash-chaining, write-once storage, or digital signing) for
  audit log entries and the `authorisation_event` table, so that even a privileged database user
  could not silently `UPDATE`/`DELETE` rows outside the application code path — tracked under
  [roadmap Production Considerations](../roadmap.md#6-production-considerations) (compliance
  posture item).
- Introduce centralized/immutable log aggregation (e.g. a SIEM) — logs are currently emitted to
  each service's stdout console only.
- Evaluate database-level audit logging (e.g. Postgres `pgaudit`) capturing raw DDL/DML
  independent of the application layer, as part of the compliance-hardening phase.
- Log/audit any manual recovery SQL (e.g. requeueing a `FAILED` outbox row) once an admin-action
  audit trail exists (see [Audit Events to Capture § Next step](#audit-events-to-capture) above) —
  this is in fact the *documented* recovery mechanism for outbox/DLT issues today, see
  [Outbox Backlog Recovery](../flows/outbox-backlog-recovery.md).

## Compliance Posture Statement

**Not certified yet.** This platform is a personal MVP/demonstration project and has **not**
undergone, and does not currently claim, any formal compliance certification (e.g. PCI-DSS,
SOC 2, ISO 27001). This statement is intentionally explicit rather than left ambiguous.

**What is already in place** and contributes toward a future compliance posture:

- An append-only financial event log (`authorisation_event`) with correlation ids and reason codes.
- Idempotency and optimistic-locking controls preventing duplicate financial side effects (relevant
  to data-integrity controls in most compliance frameworks).
- Distributed tracing giving independent corroboration of request flows.
- Documented, versioned architectural decisions (ADRs) covering the reasoning behind each control
  that does exist today.

**Planned controls** (see [Next Steps List](#next-steps--roadmap-milestones) and
[roadmap](../roadmap.md) for full detail):

- Request authentication/authorization (Spring Security + JWT) — planned as
  [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized).
- Transport encryption (TLS, Kafka SASL/mTLS, Redis ACLs) — tracked under
  [roadmap Production Considerations](../roadmap.md#6-production-considerations).
- Secrets management/rotation — same roadmap section.
- Actor-attributed audit trail — follows once authentication lands.

## Next Steps + Roadmap Milestones

| Next step | Roadmap reference | Target phase |
|-----|----------------------|----------------|
| Add request authentication/authorization | [Next Steps #1](../roadmap.md#5-next-steps-prioritized) | Next phase (highest-priority item in the roadmap's prioritized list) |
| Add an API gateway / centralized authn/authz + rate limiting | [Next Steps #4](../roadmap.md#5-next-steps-prioritized) | Next phase, after item 1 |
| Add an actor field to audit events | Depends on item 1 above (this doc, [Required Event Fields](#required-event-fields)) | Follows authentication rollout |
| Add admin-action / config-change audit events | This doc, [Audit Events to Capture](#audit-events-to-capture) | Follows authentication rollout (no admin surface exists to audit yet) |
| Add TLS / transport encryption (HTTP, Kafka, Redis) | [Production Considerations](../roadmap.md#6-production-considerations) | Infrastructure hardening phase (deployment-environment-dependent) |
| Add a secrets vault/rotation | [Production Considerations](../roadmap.md#6-production-considerations) | Infrastructure hardening phase |
| Add encryption at rest | [Production Considerations](../roadmap.md#6-production-considerations) | Infrastructure hardening phase |
| Define a formal retention/deletion policy | [Data Protection § Retention](./data-protection.md#retentiondeletion-policy) (marked TBD) | To be defined alongside compliance posture work |
| Add cryptographic log tamper-evidence / DB-level audit logging | This doc, [Log Integrity](#log-integritytamper-considerations) | Compliance-hardening phase |
| Pursue formal compliance certification | This doc, [Compliance Posture Statement](#compliance-posture-statement) | Not scheduled — would follow the above controls landing first |

## Roadmap

See [Payment Platform MVP Progress](../roadmap.md) for full detail and current sequencing, particularly:

- [§5 Next Steps](../roadmap.md#5-next-steps-prioritized) — authentication/authorization (item 1)
  and API gateway (item 4), the two highest-leverage items for advancing the audit/compliance
  next steps above.
- [§6 Production Considerations](../roadmap.md#6-production-considerations) — transport security,
  secrets management, and the broader compliance posture (audit logging, PCI-relevant controls)
  appropriate to a real payments system.

See also [Threat Model](./threat-model.md) for the risk context motivating these controls, and
[Data Protection](./data-protection.md) for the data-classification and retention detail this
audit trail is scoped against.

