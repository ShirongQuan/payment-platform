# ADR 0002: Support Full Capture and Full Reverse

- Status: Accepted
- Date: 2026-07-31

## Context

The current payment lifecycle needs clear, deterministic state transitions for the authorise, capture, and reverse flows while the platform exercises idempotency, concurrency control, event delivery, and downstream ledger projection across service boundaries.

## Decision

Define the current lifecycle around:
- full capture of an authorised amount
- full reverse of an authorised amount

This gives `auth-service` explicit terminal transitions from `AUTHORISED` to either `CAPTURED` or `REVERSED`, with corresponding events propagated asynchronously to downstream consumers.

Partial capture, partial reverse, refunds, and settlement flows are planned lifecycle extensions; see [roadmap: Production Considerations](../roadmap.md#6-production-considerations).

## Consequences

Positive:
- explicit state machine (`AUTHORISED -> CAPTURED` or `AUTHORISED -> REVERSED`)
- deterministic idempotency and concurrency semantics for the implemented lifecycle
- clear event semantics for asynchronous ledger projection

Trade-offs:
- extending the lifecycle with partial operations or refunds will require coordinated evolution of API contracts, state rules, event semantics, and ledger projection logic

## Related

- [System Context](../architecture/system-context.md)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/capture-sequence.svg">Capture Sequence</a> (sequence diagram)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/reverse-sequence.svg">Reverse Sequence</a> (sequence diagram)

