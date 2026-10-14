# ADR 0004: Use Separate Main Event and DLT Topics

- Status: Accepted
- Date: 2026-07-31

## Context

`ledger-service` processes payment events asynchronously, so individual consumer records can fail independently of the originating payment request and of other healthy records. Non-transient issues such as an invalid payload, schema mismatch, or business incompatibility need a separate operational path after the configured retry policy is exhausted.

## Decision

Use separate Kafka topics:
- main topic for normal event flow: `auth.events`
- dead-letter topic for failed records after retry policy: `auth.events.ledger.dlt`

Keep retries within the consumer processing boundary and route exhausted failures to the DLT for operational triage. This isolates failed asynchronous work from the main event stream while retaining the record for investigation and controlled replay.

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
- [Operational Runbook](../development/runbook.md)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/event-consuming.svg">Event Consuming Sequence</a> (sequence diagram)

