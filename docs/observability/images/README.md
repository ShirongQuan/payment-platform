# Observability Images

Screenshots referenced from the root [`README.md`](../../../README.md) "Observability" highlight
section.

Expected files:

- `grafana-dashboard.png` — a full-dashboard screenshot of the **Payment Platform Overview** Grafana
  dashboard (`http://localhost:3000`), giving a one-glance preview of the platform's golden-signal
  panels (API health, fraud dependency, outbox/Kafka pipeline, idempotency).
- `tempo-trace.png` — a screenshot of a single distributed trace in Grafana Explore → Tempo (e.g.
  a `POST /authorisations` trace showing the `auth-service → fraud-service` span pair and the DB
  transaction span), per
  [`docs/getting-started/demo-guide.md` § Inspect Distributed Traces](../../getting-started/demo-guide.md#inspect-distributed-traces).

These are single "quick preview" images for the root README. Per-panel screenshots used inside the
detailed telemetry reference (one per critical panel, e.g. outbox lag, DLT rate) live separately in
[`../dashboard-screenshots/`](../dashboard-screenshots/) and are wired into
[`../telemetry.md`](../telemetry.md) — see that folder's README for the full list.


