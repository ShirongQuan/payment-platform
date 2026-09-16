# Audit & Compliance

## Table of Contents

- [Audit Events to Capture](#audit-events-to-capture)
- [Required Event Fields](#required-event-fields)
- [Log Integrity/Tamper Considerations](#log-integritytamper-considerations)
- [Compliance Posture Statement](#compliance-posture-statement)
- [Gap List + Roadmap Milestones](#gap-list--roadmap-milestones)
- [Roadmap](#roadmap)

> **Reading this document:** every section is broken into **Current** (what's implemented today),
> **Gap** (what's deliberately not yet in place), and **Next step** (planned control + roadmap
> reference), since no formal audit/compliance program exists yet for this platform.

## Audit Events to Capture

### Current

`auth-service` maintains an **append-only audit trail** for every authorisation lifecycle
transition in the `authorisation_event` table (`AuthorisationEventEntity`):

- `AUTHORISATION_AUTHORISED`, `AUTHORISATION_DECLINED`, `AUTHORISATION_CAPTURED`,
  `AUTHORISATION_REVERSED` — one row per state transition, never updated after creation.
- The fraud-driven **account auto-lock** side effect is captured on the `account` row's `status`
  column (flips to `LOCKED`), but — see [Gap](#gap-list--roadmap-milestones) — no distinct audit
  event is currently emitted for the lock transition itself.
- `ledger-service` maintains a parallel, downstream **event log** (`ledger_event_log`) projected
  from the same Kafka events, giving a second, consumer-side copy of the same audit trail.
- `fraud-service` persists one row per fraud check in `fraud_evaluation` (decision, risk score, IP
  address, reasons), which functions as the audit record for the fraud-check "auth attempt".

### Gap

- **Capture / reversal** are already captured as `authorisation_event` rows (see above) — this is
  implemented, not a gap.
- **Admin actions**: there are currently no admin-only endpoints (no account-unlock endpoint, no
  replay-trigger endpoint) — all "admin" recovery actions (outbox replay, DLT reprocessing) are
  manual SQL/operator actions today (see
  [`outbox-backlog-recovery.md`](../flows/outbox-backlog-recovery.md)) and are therefore **not**
  captured in any structured audit event — they rely on database change history / operator
  discipline only.
- **Config changes**: no runtime-configurable settings exist that are changed via an API (all
  configuration is static, deployment-time `application.yml`/environment variables), so there is no
  "config change" audit event today — there is nothing to audit at runtime yet.
- **Auth attempts**: since there is no authentication layer yet, there is no "login attempt" or
  "auth failure" event to capture — every request is currently anonymous at the application layer.

### Next step

- Emit a structured audit event for the account-lock side effect (currently only visible via the
  `account.status` column, not a first-class event) — see
  [`failure-scenarios.md` § 7](../flows/failure-scenarios.md#7-fraud-decline-and-account-auto-lock).
- Once authentication (Spring Security + JWT) lands
  ([roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized)), introduce auth-attempt and
  admin-action audit events (login success/failure, role-gated action performed), and a config-
  change audit trail once any admin-configurable settings exist.

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
| **Actor** | ❌ **not captured** | There is no authenticated caller identity to attribute the event to — every event is effectively attributed to "the system", since no auth layer exists yet |
| **Explicit before/after value snapshot** | ❌ **not captured** | The event log is transition-based (event type implies the state change) rather than storing explicit before/after balance snapshots on each row |

### Gap

- **Actor**: no `actor`/`user_id` field exists on `authorisation_event`, `fraud_evaluation`, or any
  other table — this is the most significant gap for a real audit trail, since events cannot be
  attributed to a specific authenticated caller today.
- **Explicit before/after snapshots**: reconstructing "what changed" requires replaying the event
  sequence rather than reading a single row's before/after columns.

### Next step

- Add an `actor` field (populated from the JWT subject/service-account identity once
  authentication lands) to `authorisation_event` and any future admin-action audit table — see
  [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized).
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

### Gap

- **No cryptographic tamper-evidence**: there is no hash-chaining, write-once storage, or digital
  signing of audit log entries or the `authorisation_event` table. A privileged database user could
  still directly `UPDATE`/`DELETE` rows outside the application code path (e.g. via a manual SQL
  operation, which is in fact the *documented* recovery mechanism for outbox/DLT issues today — see
  [`outbox-backlog-recovery.md`](../flows/outbox-backlog-recovery.md)).
- **No centralized/immutable log aggregation**: logs are emitted to each service's stdout console
  only; there is no shipped, write-once log aggregation platform (e.g. a SIEM) in this stack today.
- **No database-level audit logging** (e.g. Postgres `pgaudit`) capturing raw DDL/DML independent of
  the application layer.

### Next step

- Evaluate database-level audit logging (e.g. `pgaudit`) or a write-once log sink as part of the
  compliance-hardening phase — tracked under
  [roadmap Production Considerations](../roadmap.md#6-production-considerations) (compliance
  posture item).
- Any manual recovery SQL (e.g. requeueing a `FAILED` outbox row) should itself be logged/audited
  once an admin-action audit trail exists (see [Next step](#next-step) above).

## Compliance Posture Statement

**Not certified yet.** This platform is a personal MVP/demonstration project and has **not**
undergone, and does not currently claim, any formal compliance certification (e.g. PCI-DSS,
SOC 2, ISO 27001). This statement is intentionally explicit rather than left ambiguous.

**Controls in progress** (see [Gap List](#gap-list--roadmap-milestones) and
[roadmap](../roadmap.md) for full detail):

- Request authentication/authorization (Spring Security + JWT) — not yet started, planned as
  [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized).
- Transport encryption (TLS, Kafka SASL/mTLS, Redis ACLs) — not yet started, tracked under
  [roadmap Production Considerations](../roadmap.md#6-production-considerations).
- Secrets management/rotation — not yet started, same roadmap section.
- Actor-attributed audit trail — not yet started, depends on authentication landing first.

**What is already in place** and contributes toward a future compliance posture:

- An append-only financial event log (`authorisation_event`) with correlation ids and reason codes.
- Idempotency and optimistic-locking controls preventing duplicate financial side effects (relevant
  to data-integrity controls in most compliance frameworks).
- Distributed tracing giving independent corroboration of request flows.
- Documented, versioned architectural decisions (ADRs) covering the reasoning behind each control
  that does exist today.

## Gap List + Roadmap Milestones

| Gap | Roadmap reference | Target phase |
|-----|----------------------|----------------|
| No request authentication/authorization | [Next Steps #1](../roadmap.md#5-next-steps-prioritized) | Next phase (highest-priority item in the roadmap's prioritized list) |
| No API gateway / centralized authn/authz + rate limiting | [Next Steps #4](../roadmap.md#5-next-steps-prioritized) | Next phase, after item 1 |
| No actor field on audit events | Depends on item 1 above (this doc, [Required Event Fields](#required-event-fields)) | Follows authentication rollout |
| No admin-action / config-change audit events | This doc, [Audit Events to Capture](#audit-events-to-capture) | Follows authentication rollout (no admin surface exists to audit yet) |
| No TLS / transport encryption (HTTP, Kafka, Redis) | [Production Considerations](../roadmap.md#6-production-considerations) | Infrastructure hardening phase (deployment-environment-dependent) |
| No secrets vault/rotation | [Production Considerations](../roadmap.md#6-production-considerations) | Infrastructure hardening phase |
| No encryption at rest | [Production Considerations](../roadmap.md#6-production-considerations) | Infrastructure hardening phase |
| No formal retention/deletion policy | [`data-protection.md` § Retention](./data-protection.md#retentiondeletion-policy) (marked TBD) | To be defined alongside compliance posture work |
| No cryptographic log tamper-evidence / DB-level audit logging | This doc, [Log Integrity](#log-integritytamper-considerations) | Compliance-hardening phase |
| No formal compliance certification | This doc, [Compliance Posture Statement](#compliance-posture-statement) | Not scheduled — would follow the above controls landing first |

## Roadmap

See [`docs/roadmap.md`](../roadmap.md) for full detail and current sequencing, particularly:

- [§5 Next Steps](../roadmap.md#5-next-steps-prioritized) — authentication/authorization (item 1)
  and API gateway (item 4), the two highest-leverage items for closing the audit/compliance gaps
  above.
- [§6 Production Considerations](../roadmap.md#6-production-considerations) — transport security,
  secrets management, and the broader compliance posture (audit logging, PCI-relevant controls)
  appropriate to a real payments system.

See also [`threat-model.md`](./threat-model.md) for the risk context motivating these controls, and
[`data-protection.md`](./data-protection.md) for the data-classification and retention detail this
audit trail is scoped against.

