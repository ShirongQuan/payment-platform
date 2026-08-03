# ADR 0002: Support Full Capture and Full Reverse Only (MVP)

- Status: Accepted
- Date: 2026-07-31

## Context

Payment operations can include partial capture, partial reverse, refunds, and settlement variants. Supporting all variants early increases domain rules, state transitions, validation complexity, and testing surface.

## Decision

For MVP scope, support only:
- full capture of an authorised amount
- full reverse of an authorised amount

Out of scope for now:
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

- `docs/architecture/system-overview.md`
- `docs/flows/capture-sequence.mmd`
- `docs/flows/reverse-sequence.mmd`

