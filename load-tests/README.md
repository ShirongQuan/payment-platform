# Load / Traffic Generation Scripts

## `generate-traffic.sh`

Generates realistic, varied traffic against `auth-service` / `fraud-service` / `ledger-service`
so it shows up across the Grafana dashboards: normal auth → capture/reversal flow, idempotency
cache hits, fraud declines under burst load, circuit breaker open/close transitions, HTTP 5xx
errors, and dead-letter-topic (DLT) publishes.

### Prerequisites

- `bash`, `curl`, `jq`
- `kcat` (`brew install kcat`) — used for the DLT phase and for delegating to `generate-dedup-events.sh`
- `uuidgen` (macOS built-in)
- `auth-service` (`:9000`), `fraud-service` (`:9010`), `ledger-service` (`:9020`) running locally
- Kafka reachable at `localhost:9092` (see `infra/docker/docker-compose.yml`)
- 3 existing, funded, `ACTIVE` accounts — one per currency (GBP/USD/EUR)
- `generate-dedup-events.sh` present in the same directory (see below) — the last phase delegates
  to it

### Idempotency keys are timestamp-scoped

Every idempotency key embeds a **run-scoped tag** (`RUN_TAG`, the last 6 digits of `date +%s` at
script start), e.g. `ikey-483920-001`, `cap-483920-001`, `rev-483920-001`. This means:

- The script can be re-run at any time without colliding with idempotency keys from a previous run
  (`accountId` + `idempotencyKey` is the uniqueness scope in `auth-service`, and keys must be
  ≤20 chars, which is why prefixes are short: `ikey-`/`cap-`/`rev-`).
- Every request across all phases in a single run gets a strictly increasing, globally unique
  sequence number, so nothing collides within a run either.

### What it does, phase by phase

A `REST_BETWEEN_STEPS_SECONDS` (default **2s**) pause is inserted between every top-level phase
below, so Grafana panels show a clear gap/boundary between phases instead of one continuous blur.

**Phase 1 — bulk authorisations (80 by default)**
- Round-robins across the 3 accounts, £1/$1/€1 per request.
- First **50** requests, one every **2s**.
- Next **30** requests, one every **1s** (faster cadence), intended to trip fraud-service's
  IP/account velocity rules and produce some `DECLINED` outcomes.
- **Every authorisation and capture call is immediately re-sent with the same idempotency key +
  payload** — the second call of each should hit the idempotent-replay path (cache hit) instead of
  re-executing the operation. Reversal calls are sent once only (no duplicate).
- Odd-numbered keys → capture; even-numbered keys → reversal.

**Phase 2 — circuit breaker open → held open → half-open → closed (+ HTTP 5xx errors)**
- Sets fraud-service to `ALWAYS_503` (every `/fraud/check` call fails with HTTP 503) — this both
  accumulates failures fast enough to trip auth-service's circuit breaker open, and generates real
  5xx samples on fraud-service's own `http_server_requests_seconds_count{status="503"}` metric.
- Fires 10 authorisations, 1s apart (`CB_OPEN_REQUEST_COUNT`/`CB_OPEN_INTERVAL_SECONDS`).
- **Holds** fraud-service in `ALWAYS_503` mode for a further 25s (`CB_HOLD_SECONDS`), so the OPEN
  state is clearly visible on the dashboard and auth-service's `waitDurationInOpenState` (15s) has
  fully elapsed.
- Resets fraud-service to `OFF`, then fires 10 more authorisations, **2s apart**
  (`CB_RECOVERY_REQUEST_COUNT`/`CB_RECOVERY_INTERVAL_SECONDS`), to observe recovery (half-open →
  closed) as trial calls succeed.

**Phase 3 — DLT (dead-letter-topic) publishes**
- Per `docs/development/runbook.md`: publishes records with `eventType=UNSUPPORTED_EVENT_TYPE`
  directly onto the `auth.events` Kafka topic via `kcat`. `ledger-service` treats this as
  non-retryable and routes it straight to `auth.events.ledger.dlt`, incrementing
  `ledger_kafka_dlt_published_total`.

**Phase 4 — dedup events**
- Delegates to the sibling `generate-dedup-events.sh` script (found relative to this script's own
  location, so it works regardless of your current working directory), passing
  `ROUNDS=$DEDUP_ROUNDS DUPLICATES_PER_EVENT=$DEDUP_DUPLICATES_PER_EVENT INTERVAL_SECONDS=$DEDUP_INTERVAL_SECONDS`
  (defaults: `ROUNDS=10 DUPLICATES_PER_EVENT=3 INTERVAL_SECONDS=1`). See its own section below for
  details — generates `ledger_event_duplicate_skipped_total` samples across all event types.

**Phase 5 — concurrency conflicts**
- Delegates to the sibling `generate-concurrency-conflicts.sh` script (found relative to this
  script's own location), passing
  `ROUNDS=$CONCURRENCY_ROUNDS PARALLEL_REQUESTS=$CONCURRENCY_PARALLEL_REQUESTS INTERVAL_SECONDS=$CONCURRENCY_INTERVAL_SECONDS`
  (defaults: `ROUNDS=5 PARALLEL_REQUESTS=6 INTERVAL_SECONDS=2`). This is the **last** phase, run
  after the `REST_BETWEEN_STEPS_SECONDS` (default 2s) gap that follows phase 4. See its own section
  below for details — generates
  `auth_concurrency_conflict_total{operation=authorise|capture|reverse, type=idempotency_race|optimistic_lock}`
  samples via truly concurrent authorise/capture/reverse races, auto-provisioning its own dedicated
  GBP account per round.

The script always resets fraud-service's failure-mode to `OFF` at the end (even on error/Ctrl-C),
via an `EXIT` trap, so it never leaves the environment in a broken state.

### Usage

```bash
cd payment-platform/load-tests
./generate-traffic.sh
```

Customize via environment variables:

```bash
# Point at a different environment
BASE_URL=http://localhost:9000 FRAUD_BASE_URL=http://localhost:9010 LEDGER_BASE_URL=http://localhost:9020 \
  ./generate-traffic.sh

# Shrink phase 1 for a quick smoke test
BULK1_COUNT=5 BULK1_INTERVAL_SECONDS=1 BULK2_COUNT=5 BULK2_INTERVAL_SECONDS=1 \
  ./generate-traffic.sh

# Fewer/faster circuit-breaker or 5xx requests
CB_OPEN_REQUEST_COUNT=3 CB_HOLD_SECONDS=5 CB_RECOVERY_REQUEST_COUNT=3 \
DLT_MESSAGE_COUNT=5 ./generate-traffic.sh

# Fewer dedup-event rounds, or shrink the pause between top-level phases
DEDUP_ROUNDS=3 DEDUP_DUPLICATES_PER_EVENT=2 REST_BETWEEN_STEPS_SECONDS=1 ./generate-traffic.sh

# Fewer/faster concurrency-conflict rounds
CONCURRENCY_ROUNDS=2 CONCURRENCY_PARALLEL_REQUESTS=4 CONCURRENCY_INTERVAL_SECONDS=1 ./generate-traffic.sh

# Use your own account IDs
GBP_ACCOUNT_ID=11111111-1111-1111-1111-111111111111 \
USD_ACCOUNT_ID=22222222-2222-2222-2222-222222222222 \
EUR_ACCOUNT_ID=33333333-3333-3333-3333-333333333333 \
./generate-traffic.sh
```

Full default run takes roughly **5–6 minutes** (phase 1: 50×2s + 30×1s ≈ 130s; phase 2 adds a 25s
hold plus 10×2s recovery ≈ 55s; phase 3 is quick; phase 4 delegates to `generate-dedup-events.sh`
with 10 rounds ≈ 60s+; phase 5 delegates to `generate-concurrency-conflicts.sh` with 5 rounds ≈ 10s+;
plus ~5×2s rest between phases). Each phase prints a `###`-bannered
description before it starts, e.g.:

```
### Testing circuit breaker + HTTP 5xx errors: setting fraud-service mode to ALWAYS_503 ###
    (every /fraud/check call now fails with HTTP 503 - this both accumulates failures
     fast enough to trip the circuit breaker OPEN, and generates real 5xx samples on
     fraud-service's own http_server_requests_seconds_count{status="503"} metric)
  -> fraud-service failure-mode set to ALWAYS_503 (http=200)
```

### Verifying results

```bash
# Circuit breaker state
curl -s http://localhost:9000/actuator/circuitbreakers

# 5xx on fraud-service
curl -s http://localhost:9010/actuator/prometheus | grep 'status="503"'

# DLT publishes
curl -s http://localhost:9020/actuator/prometheus | grep ledger_kafka

# Concurrency conflicts
curl -s http://localhost:9000/actuator/prometheus | grep auth_concurrency_conflict_total
```

## `generate-dedup-events.sh`

Generates test data for the Grafana metric:

```promql
sum by (eventType) (rate(ledger_event_duplicate_skipped_total{application="ledger-service"}[5m]) * 60)
```

### How it works

`ledger-service`'s Kafka consumer deduplicates by `eventId`: each command handler
(`AuthorisationAuthorisedHandler`/`CapturedHandler`/`ReversedHandler`) does an atomic
"insert-or-do-nothing" on `processed_event(event_id)`. If a record with the same `eventId` is
delivered again, the insert affects 0 rows and the handler short-circuits, incrementing
`ledger_event_duplicate_skipped_total{eventType=...}` instead of reprocessing it.

So the most direct way to generate this metric is exactly what it sounds like: **publish the same
Kafka record (same `eventId`) to `auth.events` more than once**, via `kcat` (same technique as the
DLT phase in `generate-traffic.sh`, documented in `docs/development/runbook.md`). This script does
that for all three ledger-relevant event types (`AUTHORISATION_AUTHORISED`/`CAPTURED`/`REVERSED`,
since the Grafana query groups `by (eventType)`), repeated over several rounds so the metric shows
up as a genuine rate over time rather than a single blip. No pre-existing account/authorisation is
required — `ledger_entry` rows are independent, FK-free projections, so fully synthetic UUIDs work.

⚠️ **`kcat` gotcha that will silently break this**: `kcat -P` treats each *line* of stdin as a
separate Kafka message. Pretty-printed JSON (e.g. `jq -n` without `-c`) is multi-line and gets
split into multiple malformed records that land straight in the DLT instead of testing dedup (this
bit us while building this script — the fix is to always use `jq -nc` for compact, single-line
JSON).

### Usage

```bash
cd payment-platform/load-tests
./generate-dedup-events.sh
```

Customize via environment variables:

```bash
# More rounds / more duplicates per event / faster cadence
ROUNDS=10 DUPLICATES_PER_EVENT=3 INTERVAL_SECONDS=1 ./generate-dedup-events.sh

# Point at a different environment
KAFKA_BROKER=localhost:9092 LEDGER_BASE_URL=http://localhost:9020 ./generate-dedup-events.sh
```

### Verifying results

```bash
curl -s http://localhost:9020/actuator/prometheus | grep ledger_event_duplicate_skipped_total
```

## `generate-concurrency-conflicts.sh`

> Also runs automatically as **Phase 5 (the last step)** of `generate-traffic.sh`, 2s after the
> preceding dedup-events phase finishes (`REST_BETWEEN_STEPS_SECONDS`). Run it standalone (below)
> if you only want to generate concurrency-conflict traffic.

Generates test data for the Grafana metric:

```promql
sum by (operation, type) (rate(auth_concurrency_conflict_total{application="auth-service"}[5m]) * 60)
```

### How it works

`auth-service` recognizes two distinct concurrency conflicts, both counted on the same metric,
distinguished by the `type` tag:

- **`type=idempotency_race`** — two (or more) requests share the same idempotency key and race on
  the unique DB constraint (`account_id`/`authorisation_id`, `event_type`, `idempotency_key`).
  Exactly one insert wins; the rest are caught as `ConcurrentIdempotencyRaceException` and safely
  resolved by re-reading and replaying the winner's response (HTTP 200, identical body for every
  racer).
- **`type=optimistic_lock`** — two *distinct*, legitimately different requests (different
  idempotency keys) both try to mutate the *same account's* balance at the same instant. Every
  authorise/capture/reverse that changes the account's balance bumps its optimistic-lock `version`
  column, so whichever request's update loses the version race gets
  `ObjectOptimisticLockingFailureException`, wrapped as `AccountConcurrencyConflictException` and
  mapped to HTTP 409 (`errorCode=ACCOUNT_CONCURRENCY_CONFLICT`) — benign infra contention between
  two legitimate requests, not a duplicate, so there is no safe replay to fall back to (the client
  is expected to retry).

**Non-obvious interaction**: authorise/capture/reverse all mutate the account's balance *before*
the idempotency-key uniqueness check is reached, so a genuine concurrent *duplicate* request that
also changes the account balance will almost always race on the account's optimistic-lock version
first — surfacing as `type=optimistic_lock`, not `type=idempotency_race`. The one reliable way to
isolate a pure `type=idempotency_race` sample is a scenario where the account balance is never
touched at all: authorise declined due to insufficient funds is exactly that case, so the script
deliberately uses an amount that exceeds the account's available balance for that sub-race. This
also means capture/reverse can only reliably demonstrate `type=optimistic_lock` with this script,
not `type=idempotency_race` (a documented, known gap).

So, per round, the script fires several *truly concurrent* requests (backgrounded with `&`, then
`wait`): an optimistic-lock race each for authorise, capture (on a freshly-seeded AUTHORISED
authorisation), and reverse (on a second freshly-seeded AUTHORISED authorisation), followed last by
an insufficient-funds idempotency-race on authorise (run last because it deliberately produces
several rapid DECLINED authorisations, which can otherwise trip fraud-service's repeated-decline
account-lock recommendation before the other sub-races get a chance to run).

**Account provisioning**: by default the script creates and funds its own dedicated GBP account
(`POST /accounts` + `POST /accounts/{id}/deposits`) fresh **before every round**, rather than reusing
a shared demo account. This avoids two real problems observed with a shared/reused account: (1)
every optimistic-lock "winner" leaves behind a reserved-but-never-captured/reversed balance, so
available balance shrinks across repeated rounds/runs until authorise starts declining for real
instead of racing; (2) enough rapid DECLINED authorisations trip fraud-service's repeated-decline
lock recommendation, after which every subsequent request on that account short-circuits as
"account not active" instead of racing at all. Pass `ACCOUNT_ID` to reuse a specific existing
account instead (skips auto-provisioning, so the known depletion/locking issues above can resurface
on repeated runs).

### Usage

```bash
cd payment-platform/load-tests
./generate-concurrency-conflicts.sh
```

Customize via environment variables:

```bash
# More rounds / more concurrent requests per race
ROUNDS=10 PARALLEL_REQUESTS=10 ./generate-concurrency-conflicts.sh

# Reuse a specific existing account instead of auto-provisioning a fresh one per round
BASE_URL=http://localhost:9000 ACCOUNT_ID=11111111-1111-1111-1111-111111111111 \
  ./generate-concurrency-conflicts.sh
```

### Verifying results

```bash
curl -s http://localhost:9000/actuator/prometheus | grep auth_concurrency_conflict_total
```

## Alternatives for heavier/sustained load

The hand-rolled bash script above is great for small, deterministic, easy-to-read demo datasets
that exercise every observability signal in this repo. If you later want more realistic or
higher-volume load for dashboard/performance testing, consider:

- **[k6](https://k6.io/)** — write the same auth → capture → reversal flow as a JS script, run
  with configurable VUs/duration (`k6 run --vus 10 --duration 5m script.js`), and get built-in
  percentile/error-rate reporting. Can also export metrics straight to Prometheus for Grafana.
- **[vegeta](https://github.com/tsenart/vegeta)** — good for simple constant-rate load against a
  single endpoint, less good for multi-step flows (auth → capture) since it doesn't chain requests.
- **[Gatling](https://gatling.io/)** — Java/Scala based, integrates naturally with this Maven
  multi-module repo if you want load tests checked into the codebase and run via
  `mvn gatling:test`.

For now, `generate-traffic.sh` should be enough to produce visible, labeled traffic (auth/capture/
reversal counts, idempotency cache hits, fraud declines, circuit breaker transitions, 5xx errors,
and DLT publishes) on the existing Grafana dashboards.

