# Observability Runbook (Lightweight)

This is a minimal, demo-scope runbook: top failure scenarios, how to detect them, and one immediate mitigation each.

A fuller runbook (detailed commands, trace queries, post-incident template) is a next-phase item: [Payment Platform MVP Progress](../roadmap.md).

## Escalation / Contact

- **Proposed primary pager:** Payments platform on-call engineer (confirm an active rota before production use).
- **Proposed secondary:** Service owner (auth/fraud/ledger) if unresolved after ~30 minutes.
- Full ownership/escalation policy: [Alerts and SLOs](./alerts-slos.md#ownership-and-escalation).

## Top 5 Failure Scenarios

| # | Scenario | How to detect | Immediate mitigation |
|---|---|---|---|
| 1 | Auth API latency/error spike | `Payment Platform Overview` -> Platform Health row: `HTTP p95 latency`, `HTTP 5xx error rate` | Check recent deploys, configuration, dependency health and logs. Roll back only if a change is implicated; consider scaling only where the deployment supports it. |
| 2 | Fraud timeout / circuit open | `Payment Platform Overview` -> Fraud Check row: `Circuit state (fraudService)`, `Circuit breaker call outcomes` | Confirm fraud-service health/network; let circuit breaker fail fast (do not disable it) and notify fraud-service owner. |
| 3 | Outbox backlog / publish lag spike | `Payment Platform Overview` -> Event Pipeline row: `Outbox backlog`, `Outbox publish lag` | Check Kafka availability, auth-service publisher/scheduler logs and outbox row statuses. `NEW`/`PUBLISHING` may recover automatically; terminal `FAILED` rows require triage and controlled manual requeue per [Outbox Backlog Recovery](../flows/outbox-backlog-recovery.md). Restart auth-service only if a stalled process is confirmed and safe to restart. |
| 4 | Kafka consumer lag growth (ledger falling behind) | `Payment Platform Overview` -> Event Pipeline row: `Consumer throughput per min vs. lag (auth.events)` | Check ledger-service logs, group rebalances, partition lag **and DLT activity**. A DLT-routed event may disappear from normal lag while its projection remains missing. Restart ledger-service only if a stall is confirmed and restart is safe; investigate/replay DLT records under the documented procedure. |
| 5 | Duplicate/idempotency conflicts | `Payment Platform Overview` -> Reliability row: `Idempotency cache outcomes/min by result` | Check retry patterns and incompatible-payload conflicts; cache hits generally indicate safe replay. Verify DB invariants/processed-event markers before concluding duplicate side effects occurred. |

## Notes

These are **triage starting points**, not instructions to restart or scale a separate publisher/consumer process. In this MVP, publisher and consumer responsibilities are hosted by the respective application services. Consult the [Operational Runbook](../development/runbook.md) for concrete commands and [Failure Scenarios](../flows/failure-scenarios.md) for expected behaviour.

- Metric/query definitions and dashboard details for each scenario are in [Telemetry Guidelines](./telemetry.md#critical-panels-golden-signals--business-critical).
- Draft alert thresholds tied to these scenarios are in [Alerts and SLOs](./alerts-slos.md).
- Deeper triage tooling (log commands, trace queries, DB/consumer lag deep-dives) and a formal post-incident template are tracked as next-phase roadmap items: [Payment Platform MVP Progress](../roadmap.md).
