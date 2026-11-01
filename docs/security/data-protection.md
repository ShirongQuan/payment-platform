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

**Current**: the MVP does not intentionally collect cardholder data (such as PAN/CVV) or
customer-identifying fields such as names and email addresses. However, it processes IP addresses
and identifiers that may be linkable to individuals. These may constitute **personal data** under
applicable data-protection law (including UK GDPR), depending on context and the information
reasonably available to identify a person. The current data model groups information as follows:

| Class                         | Fields                                                                                                                                                                                                                                  | Where                                                           |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| **Financial**                 | `available_balance`, `reserved_balance` (`account`); `amount`, `currency_code`, `merchant_reference` (`authorisation` / `authorisation_event`); `amount`, projected `balance`, raw event payloads (`ledger_entry` / `ledger_event_log`) | `auth_db`, `ledger_db`                                          |
| **Behavioural / potentially personal** | `ip_address`, `risk_score`, `decision` (`fraud_evaluation`)                                                                                                                                                                             | `fraud_db` only                                                 |
| **Operational / correlation** | `correlation_id`, `event_id`, `idempotency_key`, `traceparent`, timestamps                                                                                                                                                              | `auth_db`, `fraud_db`, `ledger_db`, Kafka headers, logs, traces |

**Next step**: assess IP addresses, account identifiers, merchant references and correlation
identifiers for linkability and data-minimisation needs now, not only when new customer fields are
introduced. Review the classification and field-level protection requirements before introducing
new identifying fields alongside authentication work — see [Roadmap](#roadmap). This document does
not establish that the platform is outside data-protection obligations.

## Where Data Lives

**Current:**

| Store                        | Data                                                                                                                                                                                          |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **PostgreSQL — `auth_db`**   | `account`, `authorisation`, `authorisation_event`, `outbox_event` tables                                                                                                                      |
| **PostgreSQL — `fraud_db`**  | `fraud_evaluation` table (amount, IP address, risk score, decision)                                                                                                                           |
| **PostgreSQL — `ledger_db`** | `ledger_entry`, `ledger_event_log`, `processed_event` tables                                                                                                                                  |
| **Redis**                    | `auth-service` idempotent-response cache (TTL-bounded, default 24h); `fraud-service` velocity-rule sliding-window counters (TTL = window + 5s)                                                |
| **Kafka**                    | `auth.events` / `auth.events.ledger.dlt` topics — event payloads (amount, currency, merchant reference, event type) plus headers (`eventId`, `correlationId`, `traceparent`, `schemaVersion`) |
| **Logs**                     | Plain-text console logs from all three services, including `traceId`, `spanId`, `correlationId`, and (in DEBUG-level authorisation logs) account ids/amounts                                  |
| **Traces**                   | OpenTelemetry spans exported to an otel-collector → Tempo, correlated via W3C `traceparent` across the HTTP → fraud call → Kafka → ledger-consumer hops                                       |

Each service only ever queries its own database — there is no cross-schema access
(`auth-service` never reads `fraud_db`/`ledger_db` directly, etc.); see
[Trust Boundaries § Data boundary](../architecture/trust-boundaries.md#3-data-boundary-financial--behavioral-data-at-rest).

## Encryption Status

### At rest

- **Current scope**: application and Docker Compose configuration do not explicitly enable
  storage encryption for PostgreSQL or Redis. Whether the host filesystem, disk or underlying
  infrastructure encrypts the container volumes is deployment-dependent and has not been verified
  as part of this MVP.
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
- **Next**: no rotation or centralised secrets store (e.g. Vault/AWS Secrets Manager) yet; the
  fallback defaults are intended for local demo use only.
- **Next step**: secrets management/rotation is tracked in
  [roadmap Production Considerations](../roadmap.md#6-production-considerations), alongside
  service-to-service auth and per-service least-privilege DB roles.

## Logging Redaction/Masking Rules

- **Current scope**: services emit plain-text console logs with a pattern including
  `[traceId,spanId] [correlationId]`; structured JSON logging is a target standard, not the
  current format. Redaction/masking is not consistently implemented. At `DEBUG` level, logs may
  include raw account identifiers and amounts (for example, `org.example.auth.authorisation` in
  `auth-service`); fraud-related IP addresses are not consistently redacted either.
- **Risk**: account identifiers, IP addresses and financial details can be sensitive even without
  names or card numbers. Traces and telemetry exports require the same data-minimisation review.
- **Target minimum-necessary policy (not yet enforced)**:
    - Do not log PAN, CVV, passwords, tokens or secrets.
    - Avoid raw account identifiers and IP addresses unless essential; mask or pseudonymise them
      where practical, with access controls appropriate to the residual linkability.
    - Restrict detailed payment-request logging to controlled development and test environments.
    - Apply equivalent redaction rules to span attributes, events and exported telemetry.
    - Review access, retention and incident handling for logs and traces.
- **Next step**: implement and test redaction rules (for example, Logback converters or structured
  logging filters) and review existing DEBUG statements before using real customer data — see
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
- **Next step**: define a documented retention schedule for PostgreSQL records, Kafka topics,
  Redis, application logs, Tempo traces, backups and exported datasets. Set appropriate archival,
  access and deletion controls for the append-only `authorisation_event`/`ledger_event_log` tables.
  Design data-subject request handling to assess applicable legal retention obligations and choose
  deletion, anonymisation or restriction as appropriate; do not assume financial audit records can
  always be erased immediately. This remains compliance-posture work — see
  [Audit & Compliance § Compliance Posture Statement](./audit-compliance.md#compliance-posture-statement)
  and [Roadmap](#roadmap). Marked explicitly as **TBD** rather than silently omitted.

## Access Controls

### Current

- **Application-level**: **none**. No authentication or authorisation exists on any HTTP endpoint
  across `auth-service`, `fraud-service`, or `ledger-service` — every exposed endpoint is equally
  reachable by any caller with network access.
- **Data-level**: schema isolation only — each service's JPA layer only ever accesses its own
  database/schema (no cross-schema queries exist in the codebase today), which limits blast radius
  but is not a credential/role-based control.
- **Ops tooling**: Grafana, Kafka UI, pgAdmin, and RedisInsight are reachable on their configured
  ports without a documented SSO/RBAC policy in this repository.

### Planned

- **RBAC / role separation**: Spring Security + JWT with customer vs. admin role separation —
  [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritised). Admin-only operations (e.g. a
  future account-unlock endpoint, outbox/DLT replay tooling) would be gated behind the admin role.
- **Service accounts**: per-service least-privilege database roles/credentials (rather than a
  shared Postgres superuser-style credential across services) —
  [roadmap Production Considerations](../roadmap.md#6-production-considerations).
- **Network policy**: restricting database/broker/cache reachability to their owning services (e.g.
  via Kubernetes network policies or a service mesh) — same roadmap section.

## Roadmap

Full sequencing and target milestones for the next steps above are tracked in
[Payment Platform MVP Progress](../roadmap.md), specifically:

- [§5 Next Steps](../roadmap.md#5-next-steps-prioritised) — items 1 (auth + role separation) and 4
  (API gateway, centralised authn/authz).
- [§6 Production Considerations](../roadmap.md#6-production-considerations) — transport security
  and network hardening (TLS, Kafka SASL/mTLS, Redis ACLs), secrets management/rotation, and
  compliance posture (audit logging, PCI-relevant controls).

See also [Threat Model](./threat-model.md) for the risk assessment these controls are meant to
close, and [Audit & Compliance](./audit-compliance.md) for the audit-trail and compliance-posture
detail.

