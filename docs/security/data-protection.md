# Data Protection

## Table of Contents

- [Data Classification](#data-classification)
- [Where Data Lives](#where-data-lives)
- [Encryption Status](#encryption-status)
- [Logging Redaction/Masking Rules](#logging-redactionmasking-rules)
- [Retention/Deletion Policy](#retentiondeletion-policy)
- [Access Controls](#access-controls)
- [Roadmap](#roadmap)

> **Reading this document:** every section describes **what's implemented today** and the
> **next steps** planned to extend it further. See [Payment Platform MVP Progress](../roadmap.md)
> for full sequencing.

## Data Classification

**Current**: this platform's data model, as implemented, holds three classes of data — no
regulated personal PII (name, card number, email, address) is collected or stored anywhere in the
current schema:

| Class | Fields | Where |
|-------|--------|-------|
| **Financial** | `available_balance`, `reserved_balance` (`account`); `amount`, `currency_code`, `merchant_reference` (`authorisation` / `authorisation_event`); `amount`, projected `balance`, raw event payloads (`ledger_entry` / `ledger_event_log`) | `auth_db`, `ledger_db` |
| **Behavioral / PII-adjacent** | `ip_address`, `risk_score`, `decision` (`fraud_evaluation`) | `fraud_db` only |
| **Operational / correlation** | `correlation_id`, `event_id`, `idempotency_key`, `traceparent`, timestamps | `auth_db`, `fraud_db`, `ledger_db`, Kafka headers, logs, traces |

**Next step**: no customer-identifying fields (name, email, physical address, card/account-holder
number) exist in the schema today, so there is currently no dedicated "PII" data class requiring
field-level masking/encryption beyond the IP address noted above. If customer-identifying fields
are introduced (e.g. alongside the planned authentication work), this classification table will be
revisited and a field-level encryption/masking review performed before storage — see
[Roadmap](#roadmap).

## Where Data Lives

**Current:**

| Store | Data |
|-------|------|
| **PostgreSQL — `auth_db`** | `account`, `authorisation`, `authorisation_event`, `outbox_event` tables |
| **PostgreSQL — `fraud_db`** | `fraud_evaluation` table (amount, IP address, risk score, decision) |
| **PostgreSQL — `ledger_db`** | `ledger_entry`, `ledger_event_log`, `processed_event` tables |
| **Redis** | `auth-service` idempotent-response cache (TTL-bounded, default 24h); `fraud-service` velocity-rule sliding-window counters (TTL = window + 5s) |
| **Kafka** | `auth.events` / `auth.events.ledger.dlt` topics — event payloads (amount, currency, merchant reference, event type) plus headers (`eventId`, `correlationId`, `traceparent`, `schemaVersion`) |
| **Logs** | Structured console logs from all three services, including `traceId`, `spanId`, `correlationId`, and (in DEBUG-level authorisation logs) account ids/amounts |
| **Traces** | OpenTelemetry spans exported to an otel-collector → Tempo, correlated via W3C `traceparent` across the HTTP → fraud call → Kafka → ledger-consumer hops |

Each service only ever queries its own database — there is no cross-schema access
(`auth-service` never reads `fraud_db`/`ledger_db` directly, etc.); see
[Trust Boundaries § Data boundary](../architecture/trust-boundaries.md#3-data-boundary-financial--behavioral-data-at-rest).

## Encryption Status

### At rest

- **Current scope**: at-rest encryption is planned for a future iteration. PostgreSQL and Redis run
  without any at-rest encryption configuration in `infra/docker`/`infra/postgres` — data is stored
  in plain form on the container volumes.
- **Next step**: add transparent data encryption (TDE), volume-level encryption, or field-level
  encryption as infrastructure-layer hardening appropriate to the target deployment environment
  (e.g. cloud-managed encrypted volumes/KMS) — see
  [roadmap Production Considerations](../roadmap.md#6-production-considerations).

### In transit

- **Current scope**: encryption in transit is planned for a future iteration. All inter-service HTTP
  calls (`client → auth-service`, `auth-service → fraud-service`, `client → ledger-service`) run
  over plain HTTP; Kafka brokers are configured with `PLAINTEXT` listeners (no SASL/TLS); Redis has
  no `requirepass`/TLS configured.
- **Next step**: add TLS termination (public API and internal service-to-service calls), authenticated/
  encrypted Kafka transport (SASL/mTLS), and Redis `requirepass`/ACLs — explicitly tracked in
  [roadmap Production Considerations](../roadmap.md#6-production-considerations).

### Secrets handling

- **Current**: database credentials are supplied via environment variables
  (`SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD`) with local-development fallback
  defaults (`postgres`/`postgres`) inlined in each service's `application.yml`. There is no
  dedicated secrets vault/manager in the stack.
- **Next**: no rotation or centralized secrets store (e.g. Vault/AWS Secrets Manager) yet; the
  fallback defaults are intended for local demo use only.
- **Next step**: secrets management/rotation is tracked in
  [roadmap Production Considerations](../roadmap.md#6-production-considerations), alongside
  service-to-service auth and per-service least-privilege DB roles.

## Logging Redaction/Masking Rules

- **Current scope**: redaction/masking is planned as part of future compliance-hardening work and is
  not yet applied. Structured logs (pattern:
  `[traceId,spanId] [correlationId] ... message`, shared across all three services) can include
  account ids and amounts at `DEBUG` level (e.g. `org.example.auth.authorisation: DEBUG` in
  `auth-service`'s `application.yml`); `fraud_evaluation`-related IP addresses are not specifically
  redacted in `fraud-service` logs either.
- **Rationale for current state**: none of the fields currently logged are classified as regulated
  PII (see [Data Classification](#data-classification)) — they are financial/operational
  identifiers (account id, amount, currency) rather than customer-identifying data.
- **Next step**: introduce a masking framework (e.g. Logback pattern converters that redact
  specific fields) and a redaction rule set (e.g. mask IP address octets, mask any future
  customer-identifying fields) as part of the compliance-hardening work, so that if
  customer-identifying fields are added in future they are redacted by default — see
  [Audit & Compliance § Log Integrity](./audit-compliance.md#log-integritytamper-considerations)
  and [Roadmap](#roadmap).

## Retention/Deletion Policy

- **Current scope**: a retention/deletion policy is planned. Today, data (accounts, authorisation
  events, fraud evaluations, ledger entries) accumulates indefinitely for the three Postgres
  databases, which is acceptable for the current MVP demo scope.
- **Partial exception**: Redis-held data is TTL-bounded by design — the idempotency response cache
  expires after `idempotency.ttl-hours` (default 24h), and velocity-rule sliding-window counters
  expire after `window + 5s`. This is an operational/performance TTL, not a compliance-driven
  retention policy.
- **Next step**: define a retention policy (how long financial/audit records must be kept vs. when
  they can be archived or purged), a right-to-erasure/deletion workflow, and an archival policy for
  the append-only `authorisation_event`/`ledger_event_log` audit tables, as part of the compliance
  posture work — see
  [Audit & Compliance § Compliance Posture Statement](./audit-compliance.md#compliance-posture-statement)
  and [Roadmap](#roadmap). Marked explicitly as **TBD** rather than silently omitted.

## Access Controls

### Current

- **Application-level**: **none**. No authentication or authorization exists on any HTTP endpoint
  across `auth-service`, `fraud-service`, or `ledger-service` — every exposed endpoint is equally
  reachable by any caller with network access.
- **Data-level**: schema isolation only — each service's JPA layer only ever accesses its own
  database/schema (no cross-schema queries exist in the codebase today), which limits blast radius
  but is not a credential/role-based control.
- **Ops tooling**: Grafana, Kafka UI, pgAdmin, and RedisInsight are reachable on their configured
  ports without a documented SSO/RBAC policy in this repository.

### Planned

- **RBAC / role separation**: Spring Security + JWT with customer vs. admin role separation —
  [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized). Admin-only operations (e.g. a
  future account-unlock endpoint, outbox/DLT replay tooling) would be gated behind the admin role.
- **Service accounts**: per-service least-privilege database roles/credentials (rather than a
  shared Postgres superuser-style credential across services) —
  [roadmap Production Considerations](../roadmap.md#6-production-considerations).
- **Network policy**: restricting database/broker/cache reachability to their owning services (e.g.
  via Kubernetes network policies or a service mesh) — same roadmap section.

## Roadmap

Full sequencing and target milestones for the next steps above are tracked in
[Payment Platform MVP Progress](../roadmap.md), specifically:

- [§5 Next Steps](../roadmap.md#5-next-steps-prioritized) — items 1 (auth + role separation) and 4
  (API gateway, centralized authn/authz).
- [§6 Production Considerations](../roadmap.md#6-production-considerations) — transport security
  and network hardening (TLS, Kafka SASL/mTLS, Redis ACLs), secrets management/rotation, and
  compliance posture (audit logging, PCI-relevant controls).

See also [Threat Model](./threat-model.md) for the risk assessment these controls are meant to
close, and [Audit & Compliance](./audit-compliance.md) for the audit-trail and compliance-posture
detail.

