# Testing Strategy

> Last updated: 2026-09-15
> Scope: payment-platform MVP with production-oriented test policy

## 1) Testing goals

### MVP expectations (what "good" means now)

- prove correctness of critical payment state transitions (`AUTHORISED`, `DECLINED`, `CAPTURED`, `REVERSED`)
- prove idempotency and concurrency behavior on the payment-critical path (authorise/capture/reverse)
- prove event integrity from `auth-service` to `ledger-service` (`outbox_event` -> Kafka -> projection)
- prove resilience behavior at demo depth (fraud timeout/open-circuit, DLT routing, duplicate-event dedup)
- provide reproducible evidence with automated tests + scripted traffic in `load-tests/`

### Production expectations (target posture)

- enforce merge gates in hosted CI, including deterministic integration tests with Testcontainers
- add stable E2E suite for top payment journeys and failure recovery paths
- track latency (p50/p95/p99), throughput (req/sec), and error-rate SLO-aligned baselines across releases
- harden non-functional testing (chaos/fault injection, long-duration soak, security and authz regression)

## 2) Test pyramid for this repository

### 2.1 Unit tests

**Responsible for**
- domain invariants and pure business rules (amount validation, status transitions, reason-code mapping)
- deterministic logic in service methods with collaborators mocked/stubbed
- fast feedback on edge cases and regressions

**Not responsible for**
- SQL correctness, transaction boundaries, or Flyway migrations
- Kafka serialization/headers, outbox polling behavior, Redis wiring
- end-to-end API contracts across multiple services

### 2.2 Integration tests

**Responsible for**
- Spring Boot wiring and behavior with real infrastructure dependencies
- persistence semantics in Postgres (constraints, optimistic locking, idempotency uniqueness rules)
- Redis-backed idempotency cache and fraud velocity checks
- Kafka consumer/producer interactions, DLT behavior, and ledger dedup (`processed_event`)

**Not responsible for**
- front-to-back user journey confidence alone (that belongs to E2E)
- high-scale performance characterization (that belongs to load testing)

### 2.3 End-to-end tests

**Responsible for**
- key payment journeys across service boundaries (auth -> fraud -> outbox -> Kafka -> ledger)
- contract-level behavior (HTTP status/body + eventual ledger visibility)
- confidence that deployment wiring and core business paths work together

**Not responsible for**
- exhaustive branch coverage of all business rules
- precise root-cause diagnosis (unit/integration tests should isolate failures faster)

## 3) Test environments and tooling

- **Frameworks:** JUnit 5 via `spring-boot-starter-test`, AssertJ/Mockito from Spring test stack
- **Spring test slices:** `@WebMvcTest`, JPA tests, and `@SpringBootTest` for full wiring
- **Datastores:** H2 for fast repository checks where appropriate; Postgres Testcontainers for production-like persistence behavior
- **Messaging/cache:** Kafka and Redis behavior validated in integration tests; mock/stub only where interaction is not the subject of the test
- **Containerized infra:** `spring-boot-testcontainers` + `org.testcontainers:junit-jupiter`
- **Traffic/load scripts:** `load-tests/generate-traffic.sh`, `load-tests/generate-dedup-events.sh`, `load-tests/generate-concurrency-conflicts.sh`

### Mock/stub strategy

- mock external boundaries when validating local logic (for example, fraud client behavior in auth unit tests)
- use real dependencies (containerized) when validating persistence semantics, concurrency, or message flow
- do not mock the behavior currently being claimed as evidence (idempotency uniqueness, optimistic locking, outbox/DLT)

## 4) CI gates

### Current MVP gate

- hosted CI workflow exists at `.github/workflows/ci.yml` and runs `mvn -B -ntp clean verify` on push/PR
- local gate remains useful for fast feedback before pushing: run `mvn test`
- merge blocking still depends on GitHub branch protection/rulesets requiring the CI status check

### Target merge gate (next step)

- unit + integration tests must pass on every PR and be marked as required status checks for `main`
- smoke E2E journey must pass (`authorise -> capture` and `authorise -> reverse`)
- static quality checks (format/lint if added later) must pass
- load-regression check should block merge when latency/error thresholds regress beyond agreed tolerance

## 5) Coverage philosophy

Coverage percentage is a lagging indicator. We optimize for risk-first confidence:

- prioritize critical invariants over raw percentage targets
- ensure every state transition and illegal transition on payment entities is tested
- ensure idempotency guarantees are tested for same-key replay and same-key-different-payload conflict
- ensure eventual consistency checks exist for outbox publication and ledger projection
- treat concurrency and duplicate-event handling as first-class correctness requirements

### Critical paths that must always have strong coverage

- `POST /authorisations` success and decline reasons
- `POST /authorisations/{id}/captures` and `{id}/reversals` state guards
- unique constraint and replay logic in `authorisation_event`
- optimistic locking conflict mapping (`409 ACCOUNT_CONCURRENCY_CONFLICT`)
- outbox to Kafka to ledger projection correctness (including duplicate-event skip)




