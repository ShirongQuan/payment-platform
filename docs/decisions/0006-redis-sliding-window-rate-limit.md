# ADR 0006: Use Redis for Sliding-Window Rate Limiting (Fraud Service)

- Status: Accepted
- Date: 2026-08-13

## Context

Velocity-based fraud rules need to answer "has this dimension (account/IP/card) exceeded N events in the last T
seconds?" under concurrent traffic across potentially multiple `fraud-service` instances. An in-memory counter per
instance would:

- not be shared across horizontally scaled instances
- lose state on restart/deploy
- be prone to read-then-write races when incrementing counters concurrently

A shared, low-latency, atomic counter store is required.

## Decision

Use Redis sorted sets to implement a sliding-window rate limiter in `fraud-service`, driven by an atomic Lua script:

- key shape: `fraud:sw:{dimension}:{name}` (for example `fraud:sw:account:{accountId}`)
- each check adds a unique member (`timestamp-uuid`) with score = event timestamp (ms)
- the Lua script atomically: trims entries older than the window, adds the new entry, counts remaining entries, and
  sets a TTL, all in a single round trip
- TTL = `window + 5s`, so idle keys expire automatically instead of accumulating forever
- `count > maxRequests` means the dimension is "too frequent" for that rule

The add-trim-count sequence executes server-side via `RedisScript` (`scripts/sliding_window_rate_limit.lua`,
registered in `RedisLuaConfig`) so concurrent requests against the same key cannot race on a read-then-write basis.

## Consequences

Positive:
- accurate sliding-window counting shared across all `fraud-service` instances
- atomic server-side execution removes check-then-increment race conditions
- self-cleaning via TTL; no separate cleanup job needed
- low latency (single Redis round trip) suitable for the fraud check hot path

Trade-offs:
- adds a hard runtime dependency on Redis availability for velocity rules
- Redis outage/latency directly affects fraud-check latency/availability for velocity-based rules
- sorted-set memory usage grows with request volume within the active window per dimension

## Alternatives Considered

- in-memory counters (rejected: not shared across instances, lost on restart)
- fixed-window counters (rejected: allows bursts at window boundaries, e.g. 2x rate)
- token bucket in-memory library (Bucket4j) (rejected: same cross-instance sharing gap without an external store)
- database-backed counters (rejected: higher latency, more write load than Redis for a high-frequency check)

## Related

- `docs/decisions/0007-idempotency-store-and-key-policy.md`
- `docs/decisions/0008-resilience4j-circuit-breaker-policy.md`
- `fraud-service/src/main/java/org/example/fraud/application/VelocityServiceRedisImpl.java`
- `fraud-service/src/main/java/org/example/fraud/RedisLuaConfig.java`
- `fraud-service/src/main/resources/scripts/sliding_window_rate_limit.lua`

