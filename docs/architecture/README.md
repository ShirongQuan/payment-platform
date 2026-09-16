# Architecture Docs Index

C4-style architecture documentation for the Payment Platform, from broadest zoom to most detailed.

## Diagrams (C4)

- [System Context (L1)](./system-context.md) ([`system-context.mmd`](./system-context.mmd)) — the platform as a
  single external-facing box, its actors (Customer/Client App, Admin/Ops), and their major interactions. No
  internal services are shown at this level.
- [Containers (L2)](./containers.md) ([`containers.mmd`](./containers.mmd)) — zooms into that box: the
  deployable runtime units (`auth-service`, `fraud-service`, `ledger-service`), platform infrastructure
  (Postgres, Redis, Kafka), and the observability stack (OTel Collector, Tempo, Prometheus, Grafana), with their
  responsibilities, tech stack, owned data, and sync/async interactions.
- [Components — auth-service (L3)](./components-auth-service.md)
  ([`components-auth-service.mmd`](./components-auth-service.mmd),
  [`authorise-happy-path.mmd`](./authorise-happy-path.mmd)) — internals of `auth-service` only: component
  diagram (controllers, application services, domain, repositories, outbox, outbound clients), a mini
  "authorise happy path" sequence, a responsibility table, and boundary notes (transaction boundaries,
  idempotency/concurrency points, external calls).

## Security

- [Trust Boundaries](./trust-boundaries.md) ([`trust-boundaries.mmd`](./trust-boundaries.mmd)) — public API /
  internal service / data / messaging boundaries and what each enforces at the application level today.
  Infrastructure-level hardening (TLS, network policy, broker/cache auth, etc.) and planned application-level
  controls (e.g. authentication) are linked out to the [roadmap](../roadmap.md) rather than assessed here.

## Related

- [Flow Diagrams](../flows/README.md) — detailed sequence diagrams per use case (authorise/capture/reverse,
  idempotency, event publishing/consuming).
- [Data Model](../data/data-model.md) — table-level detail per service database.
- [ADRs](../decisions/README.md) — the "why" behind the decisions referenced throughout these docs.




