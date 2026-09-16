# Observability Runbook (Lightweight)

This is a minimal, demo-scope runbook: top failure scenarios, how to detect them, and one immediate mitigation each.

A fuller runbook (detailed commands, trace queries, post-incident template) is a next-phase item: [`docs/roadmap.md`](../roadmap.md).

## Escalation / Contact

- **Primary pager:** Payments platform on-call engineer.
- **Secondary:** Service owner (auth/fraud/ledger) if unresolved after ~30 minutes.
- Full ownership/escalation policy: [`alerts-slos.md`](./alerts-slos.md#ownership-and-escalation).

## Top 5 Failure Scenarios

| # | Scenario | How to detect | Immediate mitigation |
|---|---|---|---|
| 1 | Auth API latency/error spike | `Payment Platform Overview` -> Platform Health row: `HTTP p95 latency`, `HTTP 5xx error rate` | Check recent deploys/config changes; roll back if correlated. Otherwise scale up auth-service instances if under load. |
| 2 | Fraud timeout / circuit open | `Payment Platform Overview` -> Fraud Check row: `Circuit state (fraudService)`, `Circuit breaker call outcomes` | Confirm fraud-service health/network; let circuit breaker fail fast (do not disable it) and notify fraud-service owner. |
| 3 | Outbox backlog / publish lag spike | `Payment Platform Overview` -> Event Pipeline row: `Outbox backlog`, `Outbox publish lag` | Check Kafka broker availability and outbox worker logs; restart the outbox publisher if it is stuck. |
| 4 | Kafka consumer lag growth (ledger falling behind) | `Payment Platform Overview` -> Event Pipeline row: `Consumer throughput per min vs. lag (auth.events)` | Check consumer logs for errors/rebalance loops; restart the ledger consumer if stalled. |
| 5 | Duplicate/idempotency conflicts | `Payment Platform Overview` -> Reliability row: `Idempotency cache outcomes/min by result` | Check for client retry storms on the same idempotency key; confirm no duplicate side effects were created. |

## Notes

- Metric/query definitions and dashboard details for each scenario are in [`telemetry.md`](./telemetry.md#critical-panels-golden-signals--business-critical).
- Draft alert thresholds tied to these scenarios are in [`alerts-slos.md`](./alerts-slos.md).
- Deeper triage tooling (log commands, trace queries, DB/consumer lag deep-dives) and a formal post-incident template are tracked as next-phase roadmap items: [`docs/roadmap.md`](../roadmap.md).
