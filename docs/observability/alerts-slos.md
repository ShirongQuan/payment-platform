# Alerts and SLOs

This document is the initial operating draft for service-level objectives and alerting.

For gaps we have today, add concrete follow-up tasks in next phase planning: [`docs/roadmap.md`](../roadmap.md).

## Current Status

> No formal SLOs yet.

We currently collect core telemetry and have partial dashboards, but we have not ratified production SLO contracts.

## Candidate SLIs/SLOs (Initial Proposal)

These are draft targets to be reviewed with product and platform owners.

| Area | Candidate SLI | Candidate SLO (30-day window) |
|---|---|---|
| API availability | Successful auth responses / total auth requests | >= 99.9% |
| API latency | p95 latency for `POST /authorisations` | <= 300 ms |
| API latency | p99 latency for `POST /authorisations` | <= 800 ms |
| Error budget | 5xx ratio for auth API | <= 0.2% |
| Fraud dependency | p95 fraud call latency | <= 200 ms |
| Outbox durability | Max outbox publish lag | <= 120 s |
| Kafka reliability | Publish failure ratio | <= 0.1% |
| Idempotency safety | Duplicate processing escapes | 0 known escapes |

## Proposed Initial Alerts (Draft Thresholds)

## Page-worthy alerts

- API 5xx ratio > 2% for 10m.
- API p99 latency > 1.5s for 10m.
- Fraud timeout ratio > 5% for 10m.
- Circuit breaker open for fraud dependency for > 5m.
- Outbox backlog > 5,000 messages for 15m.
- Outbox max lag > 300s for 10m.
- Kafka publish failures > 1% for 10m.
- Consumer lag growing continuously for 15m.

## Ticket/async alerts

- Idempotency conflict rate increase > 3x baseline for 30m.
- DLT rate > baseline threshold for 30m.
- Dashboard scrape gaps > 2 intervals.

## Ownership and Escalation

- **Primary pager target:** Payments platform on-call engineer.
- **Secondary escalation:** Service owner (auth/fraud/ledger) for sustained incidents > 30 minutes.
- **Tertiary escalation:** Platform/SRE and incident commander for cross-service impact.

### Routing notes

- Auth API and outbox alerts route to auth-service owner + on-call.
- Fraud latency/circuit alerts route to fraud-service owner + on-call.
- Kafka lag/failure alerts route to platform + affected consumer owner.

## Adoption Plan (Next Phase)

1. Convert candidate SLOs into approved service contracts.
2. Set baselines using 2-4 weeks of production-like traffic.
3. Tune thresholds to reduce noise while preserving early detection.
4. Add runbook links directly in every alert payload.
5. Review monthly error-budget policy and escalation quality.

