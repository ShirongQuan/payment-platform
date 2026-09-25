# Threat Model

## Table of Contents

- [Scope / System Boundaries](#scope--system-boundaries)
- [Assets to Protect](#assets-to-protect)
- [Threat Scenarios](#threat-scenarios)
- [Risk Rating](#risk-rating)
- [Mitigations](#mitigations)
- [Residual Risks & Assumptions](#residual-risks--assumptions)
- [Roadmap](#roadmap)

> **Reading this document:** every section below is broken into **Current** (what exists in the
> code/config today), **Gap** (what is deliberately not yet implemented), and **Next step** (the
> planned control and where it's tracked). No authentication/authorization is implemented yet on
> any service in this platform — that is called out explicitly rather than implied, and the
> [roadmap](../roadmap.md) is the source of truth for target phasing.

## Scope / System Boundaries

**Current**: this threat model covers the application-layer boundaries already documented in
[`trust-boundaries.md`](../architecture/trust-boundaries.md):

1. **Public API boundary** — clients call `auth-service` (`:9000`) and `ledger-service` (`:9020`,
   read-only) directly over HTTP.
2. **Internal service boundary** — `auth-service` calls `fraud-service` (`:9010`) over HTTP within
   the same deployment network; `fraud-service` is not intended to be reachable externally.
3. **Data boundary** — three PostgreSQL databases (`auth_db`, `fraud_db`, `ledger_db`) on a shared
   Postgres instance, plus Redis (idempotency cache, velocity counters).
4. **Messaging boundary** — Kafka topics `auth.events` / `auth.events.ledger.dlt` on a 3-broker
   KRaft cluster, `auth-service` (producer) → `ledger-service` (consumer).
5. **Ops boundary** — Grafana, Kafka UI, pgAdmin, RedisInsight for observability/administration.

**Gap**: this model does not yet assess the system against a formal production security standard
(e.g. PCI-DSS control mapping) — see [`audit-compliance.md`](./audit-compliance.md) for the
compliance posture statement.

**Next step**: extend scope to cover a planned API gateway ingress boundary once implemented — see
[roadmap Next Steps #4](../roadmap.md#5-next-steps-prioritized).

## Assets to Protect

| Asset | Where it lives | Current protection |
|-------|------------------|------------------------|
| **Funds integrity** (account balances, reservations) | `auth_db.account` (`available_balance`, `reserved_balance`) | Optimistic locking (`@Version`) + idempotency uniqueness constraints prevent double-reserve/double-capture — see [`concurrency-consistency.md`](../reliability/concurrency-consistency.md) |
| **Authorisation/event audit trail** | `auth_db.authorisation`, `auth_db.authorisation_event` | Append-only event table capturing every lifecycle transition with `correlation_id`, `event_type`, `reason_code`, timestamps |
| **PII-adjacent behavioral data** (IP address, risk score, fraud decision) | `fraud_db.fraud_evaluation` | Schema-level isolation — only `fraud-service` reads/writes `fraud_db`; the IP address and raw risk evidence are never echoed back into `auth_db` or the ledger, only the approve/decline outcome is |
| **API credentials / secrets** (DB passwords) | Environment variables with local-dev defaults in each service's `application.yml` (e.g. `SPRING_DATASOURCE_PASSWORD:postgres`) | Overridable via environment variables at deploy time; **no secrets vault/manager in place today** — see [`data-protection.md`](./data-protection.md#secrets-handling) |
| **Idempotency response cache** | Redis (`auth-service`), TTL-bounded (24h default) | Treated as sensitive-adjacent since the cached value mirrors the original financial response |

## Threat Scenarios

| # | Scenario | Description | Current exposure |
|---|----------|--------------|----------------------|
| 1 | **Replay** | An attacker or misbehaving client replays a captured HTTP request (e.g. a valid authorise/capture call) to attempt a duplicate financial effect. | Mitigated for the payment-critical path: `idempotencyKey`-scoped uniqueness + Redis replay cache return the identical prior response rather than re-executing (see [ADR 0005](../decisions/0005-idempotency-and-concurrency.md)). **Not** mitigated for `POST /accounts` / `POST /accounts/{id}/deposits`, which are not idempotent today. |
| 2 | **Tampering** | An attacker with network access modifies a request in flight (no TLS today) or forges a request entirely (no request authentication today). | Not mitigated at the transport or request-authentication layer — see [Gap](#mitigations) below. Bean validation rejects malformed payloads but cannot distinguish a legitimate client from an attacker. |
| 3 | **Privilege abuse** | Since there is no authentication/authorization, every caller effectively has the same (full) access to every endpoint on a given service, including operationally sensitive ones (e.g. would-be admin actions). | Currently **no privilege separation exists** — there is no concept of "admin" vs "customer" caller. This is the platform's most significant current security gap. |
| 4 | **Fraud automation** (scripted/bot-driven authorise attempts, account probing) | An automated client submits a high rate of authorise requests to probe balances, trigger declines, or abuse the account-lock side effect. | Partially mitigated: `fraud-service`'s Redis-backed sliding-window velocity rules flag >3 requests/5s from the same IP or account (`IP_VELOCITY_EXCEEDED`, `ACCOUNT_VELOCITY_EXCEEDED`) and feed the risk score; a high risk score or repeated-decline pattern can auto-`LOCK` the account. This is a business-layer signal, not infrastructure-level rate limiting/blocking (see [ADR 0006](../decisions/0006-redis-sliding-window-rate-limit.md)). |
| 5 | **Data exposure via logs/traces** | Financial fields (amount, account id) and behavioral fields (IP address) appear in plaintext in application logs and trace spans, since none of this data is classified as regulated PII today. | No masking/redaction is applied; see [`data-protection.md` § Logging Redaction](./data-protection.md#logging-redactionmasking-rules) for the full assessment. |
| 6 | **Credential/secret leakage** | Local-dev default DB credentials (`postgres`/`postgres`) are checked into `application.yml` as fallback values. | Scoped to this demo's single-host `docker-compose` network, and the fallback only applies when the environment variable is unset. Secrets management/rotation is tracked as a roadmap item for any future deployment beyond local demo use — see [`data-protection.md` § Secrets Handling](./data-protection.md#secrets-handling). |

## Risk Rating

| Threat scenario | Likelihood | Impact | Overall risk | Rationale |
|-------------------|------------|--------|----------------|-----------|
| Tampering (no TLS, no request auth) | High (any network-adjacent actor) | High (funds/data integrity) | **High** | No authentication exists on any endpoint; this is the platform's top-priority gap. |
| Privilege abuse (no authz/role separation) | High | High | **High** | Every caller has equal access to every endpoint, including the payment-critical path. |
| Replay (non-idempotent endpoints) | Medium (`POST /accounts`, deposits only) | Medium | **Medium** | Payment-critical endpoints are already protected; only account-creation/deposit endpoints are exposed. |
| Fraud automation | Medium | Medium | **Medium** | Velocity-based signals exist and feed risk scoring/auto-lock, but there's no hard request-rate ceiling — a sufficiently distributed attacker could still stay under the velocity thresholds. |
| Data exposure via logs/traces | Low | Low–Medium | **Low–Medium** | No regulated PII is stored today (see [`data-protection.md`](./data-protection.md#data-classification)); exposure is of financial amounts/IPs, not names/card numbers. |
| Credential/secret leakage | Low (single-host demo topology) | Medium (would grow with wider deployment) | **Low (in this demo scope)** | The pattern is appropriate for local demo use; secrets management/rotation is tracked as a roadmap item for any future deployment. |

## Mitigations

### Implemented

- **Idempotency & replay protection** on the payment-critical path (authorise/capture/reverse,
  fraud check) — unique DB constraints + Redis response cache (see
  [`concurrency-consistency.md`](../reliability/concurrency-consistency.md#idempotency-points)).
- **Optimistic locking** preventing lost updates / double-spend under concurrent writers.
- **Fraud risk scoring + velocity rules** (IP/account sliding window) feeding an approve/decline
  decision and an automatic account lock for high-risk/repeated-decline patterns.
- **Schema-level data isolation** — each service only ever queries its own database; `fraud_db`'s
  IP address/risk evidence is never echoed into `auth_db` or the ledger.
- **Correlation-id-based audit trail** — every request gets a correlation id
  (`CorrelationIdFilter`, `shared-spring-lib`) propagated across HTTP calls and into Kafka headers,
  plus a W3C `traceparent`, giving a consistent trace across services and async boundaries (see
  [`audit-compliance.md`](./audit-compliance.md)).
- **Bean validation** rejecting malformed input (`400`) before it reaches domain logic.
- **Circuit breaker + time limiter** on the `auth-service` → `fraud-service` call, bounding
  worst-case latency/failure impact from that dependency (see
  [`resilience-patterns.md`](../reliability/resilience-patterns.md#circuit-breaker-policy)).

### Planned Next

- **Request authentication/authorization** (Spring Security + JWT, customer vs. admin role
  separation) — [roadmap Next Steps #1](../roadmap.md#5-next-steps-prioritized).
- **API gateway** for centralized authn/authz enforcement and generic rate limiting —
  [roadmap Next Steps #4](../roadmap.md#5-next-steps-prioritized).
- **Service-to-service authentication** (mTLS or signed internal tokens) between `auth-service` and
  `fraud-service` — [roadmap Production Considerations](../roadmap.md#6-production-considerations).
- **Transport encryption** (TLS for public API + internal calls, Kafka SASL/mTLS, Redis
  `requirepass`/ACLs) and **topic/DB-level ACLs** — same roadmap section.
- **Secrets management/rotation** replacing today's environment-variable-with-default pattern —
  same roadmap section.
- **Idempotency coverage extension** to `POST /accounts` and `POST /accounts/{id}/deposits** —
  [roadmap Next Steps #6](../roadmap.md#5-next-steps-prioritized).

## Residual Risks & Assumptions

- **Assumption**: the current deployment target is a single-host `docker-compose` demo/MVP
  environment. The roadmap tracks the additional hardening (authn/authz, transport encryption,
  secrets management) that a wider deployment would build on top of this foundation.
- **Residual risk**: even after planned authn/authz lands, generic infra-level rate limiting and
  WAF-style protections remain a separate, still-unaddressed layer — tracked as a distinct roadmap
  item rather than assumed to be covered by request authentication alone.
- **Residual risk**: velocity-based fraud signals are per-dimension (IP/account) and Redis-backed;
  a sufficiently distributed or slow-and-low attacker could still stay under the current thresholds
  (3 events/5s). This is a tuning/roadmap concern, not a design flaw in the sliding-window mechanism
  itself.
- **Residual risk**: circuit breaker state is per `auth-service` instance (no shared state across
  instances) — different instances can independently be open/closed for the fraud dependency,
  which is an accepted trade-off documented in [ADR 0008](../decisions/0008-resilience4j-circuit-breaker-policy.md).

## Roadmap

See [`docs/roadmap.md`](../roadmap.md) — in particular
[§5 Next Steps](../roadmap.md#5-next-steps-prioritized) (items 1 and 4 for authn/authz and API
gateway) and [§6 Production Considerations](../roadmap.md#6-production-considerations) (transport
security, network hardening, secrets management, compliance posture) for full target-state detail
and sequencing.

