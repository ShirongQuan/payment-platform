# Flow Diagram Images

Rendered diagram referenced from the root [`README.md`](../../../README.md) "Demo Journey" section.

Files:

- `payment-lifecycle-happy-path.svg` / `.png` — a rendered export of the simplified happy-path
  flow (authorise → capture → outbox publish → ledger projection), sourced from
  [`../diagrams/payment-lifecycle-happy-path.mmd`](../diagrams/payment-lifecycle-happy-path.mmd).
  This is a condensed, README-friendly view; it intentionally omits idempotency, concurrency, and
  failure-handling branches — see the per-endpoint sequence diagrams
  (`authorise-sequence.mmd`, `capture-sequence.mmd`, `reverse-sequence.mmd`, etc.) in
  [`../diagrams/`](../diagrams/) for the full detail.

The `.mmd` files in [`../diagrams/`](../diagrams/) are the maintained source of truth for these
diagrams — see [`docs/flows/README.md`](../README.md) for the full diagram index; this folder only
holds a rendered snapshot for the root README's preview.

