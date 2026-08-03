# ADR 0004: Use Separate Main Event and DLT Topics

- Status: Accepted
- Date: 2026-07-31

## Context

Some consumed messages fail due to non-transient issues (invalid payload/schema mismatch/business incompatibility). Reprocessing those messages indefinitely on the main topic can block or degrade healthy traffic.

## Decision

Use separate Kafka topics:
- main topic for normal event flow: `auth.events`
- dead-letter topic for failed records after retry policy: `auth.events.ledger.dlt`

Keep retries on the consumer side and route exhausted failures to DLT for operational triage.

## Consequences

Positive:
- isolates poison messages from normal processing
- preserves failed records for investigation/replay
- keeps main consumer throughput stable

Trade-offs:
- requires DLT monitoring and operational runbook
- adds topic management and replay procedures

## Related

- `infra/kafka/create-topics.sh`
- `docs/flows/event-consuming.mmd`
- `docs/development/runbook.md`

