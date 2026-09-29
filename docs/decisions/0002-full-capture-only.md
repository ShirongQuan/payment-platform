# ADR 0002: Support Full Capture and Full Reverse Only (MVP)

- Status: Accepted
- Date: 2026-07-31

## Context

Payment operations can include partial capture, partial reverse, refunds, and settlement variants. Supporting all variants early increases domain rules, state transitions, validation complexity, and testing surface.

## Decision

For MVP scope, support only:
- full capture of an authorised amount
- full reverse of an authorised amount

Out of scope for now (see [roadmap: Production Considerations](../roadmap.md#6-production-considerations)):
- partial capture
- partial reverse
- refunds/settlement flows

## Consequences

Positive:
- simpler and safer state machine (`AUTHORISED -> CAPTURED` or `AUTHORISED -> REVERSED`)
- lower implementation and testing complexity
- faster iteration on core idempotency/concurrency/eventing behavior

Trade-offs:
- does not cover many real payment lifecycle scenarios
- future expansion will require migration of API, state rules, and projection logic

## Related

- [System Context](../architecture/system-context.md)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/capture-sequence.svg">Capture Sequence</a> (sequence diagram)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/reverse-sequence.svg">Reverse Sequence</a> (sequence diagram)

