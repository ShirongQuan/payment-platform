# Alerts and SLOs

## Table of Contents

- [Current Status](#current-status)
- [Candidate SLIs/SLOs (Initial Proposal)](#candidate-slisslos-initial-proposal)
- [Proposed Initial Alerts (Draft Thresholds)](#proposed-initial-alerts-draft-thresholds)
    - [Page-worthy alerts](#page-worthy-alerts)
    - [Ticket/async alerts](#ticketasync-alerts)
- [Ownership and Escalation](#ownership-and-escalation)
- [Adoption Plan (Next Phase)](#adoption-plan-next-phase)

This document is the initial operating draft for service-level objectives and alerting.

## Current Status

We currently collect core telemetry and have dashboards, but **production SLO contracts, alert-rule deployment, paging integrations and escalation ownership are not verified by this document**. All thresholds and routing below are proposals, not a claim that Prometheus/Alertmanager is configured to send them.

## Candidate SLIs/SLOs (Initial Proposal)

These are **proposed 30-day targets**, not approved production contracts. Each needs an agreed scope, denominator, low-traffic treatment and measurement rule before adoption. Business declines (e.g. `INSUFFICIENT_FUNDS`, fraud `DECLINE`) should not automatically count as service unavailability: they may be valid responses to a functioning API.

| Area | Candidate SLI and measurement rule | Candidate target (30-day window) |
|---|---|---|
| API availability | Good requests / eligible `POST /authorisations` requests; define good as non-5xx responses, including valid business declines, and document excluded client errors | ≥ 99.9% |
| API latency | p95 and p99 of eligible `POST /authorisations` request durations, measured from the HTTP server histogram; specify treatment of errors and low sample counts | p95 ≤ 300 ms; p99 ≤ 800 ms |
| API 5xx | HTTP 5xx responses / eligible auth API responses, counted by request rather than unique customer operation | ≤ 0.2% (draft guardrail; **not equivalent** to the 99.9% availability objective) |
| Fraud dependency | p95 client-observed fraud-check duration; define whether timeouts and breaker-rejected calls are included or reported separately | ≤ 200 ms |
| Outbox delivery | Define either p95 successful-publish lag **or** oldest outstanding event age; these require different measurements | ≤ 120 s (definition pending) |
| Kafka publishing | Failed publish **attempts** / total publish attempts, including retries; report terminally `FAILED` outbox rows separately | ≤ 0.1% |
| Idempotency safety | Confirmed duplicate business side effects identified through DB invariants, incident investigation or reconciliation; not inferred from cache hits | 0 known escapes |

**Measurement caveats:** A p95 histogram cannot prove a maximum-lag target. The existing `auth_outbox_backlog` gauge excludes terminally `FAILED` rows; inspect the outbox table for those. For a true pending-age alert, add an appropriate metric/query. API availability and 5xx targets should be reconciled into one coherent error-budget policy before ratification.

## Proposed Initial Alerts (Draft Thresholds)

### Page-worthy alerts

- API 5xx ratio > 2% for 10m.
- API p99 latency > 1.5s for 10m.
- Fraud timeout ratio > 5% for 10m **once a timeout-specific numerator and eligible-call denominator exist**; until then, monitor failed/not-permitted calls as separate signals.
- Circuit breaker open for fraud dependency for > 5m.
- Outbox backlog > 5,000 messages for 15m.
- Oldest outstanding outbox-event age > 300s for 10m **once instrumented**; do not substitute published-event p95 lag. Also detect terminally `FAILED` rows independently.
- Failed publish attempts / all publish attempts > 1% for 10m, using `auth_outbox_publish_attempts_total{result}` and verifying the actual result labels.
- Consumer lag growing continuously for 15m; monitor DLT publishes separately, since a DLT-routed event may no longer contribute to lag.

### Ticket/async alerts

- Idempotency conflict rate > 3× an established baseline for 30m, with a minimum traffic threshold to avoid low-volume noise.
- DLT rate > baseline threshold for 30m.
- Dashboard scrape gaps > 2 intervals.

## Ownership and Escalation

**Proposed routing only:** confirm named owners, rota coverage, notification channels and escalation policy before enabling paging.

- **Primary pager target:** Payments platform on-call engineer.
- **Secondary escalation:** Service owner (auth/fraud/ledger) for sustained incidents > 30 minutes.
- **Tertiary escalation:** Platform/SRE and incident commander for cross-service impact.

### Routing notes

- Auth API and outbox alerts route to auth-service owner + on-call.
- Fraud latency/circuit alerts route to fraud-service owner + on-call.
- Kafka lag/failure alerts route to platform + affected consumer owner.

## Adoption Plan (Next Phase)

1. Define precise SLI queries, eligible traffic, business-decline policy and error-budget relationship; approve the resulting service contracts.
2. Set baselines using 2-4 weeks of production-like traffic.
3. Tune thresholds to reduce noise while preserving early detection.
4. Implement and test Prometheus/Alertmanager rules, notification routing and runbook links; confirm which alerts are actually deployed.
5. Review monthly error-budget policy and escalation quality.

