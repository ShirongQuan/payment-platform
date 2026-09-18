# Load Tests

Executable traffic-generation scripts for the payment platform. They produce realistic, labeled
traffic (auth → capture/reverse flow, idempotency replay, circuit-breaker transitions, DLT
publishes, duplicate-event dedup, concurrency conflicts) that shows up across the Grafana
dashboards, for demos and manual verification.

> ⚠️ **Run these only against a local or dedicated test environment.** Never target production.
> Use synthetic accounts/data only (the concurrency script auto-provisions its own throwaway
> account per round).

For **why** these scenarios exist, the workload model, concurrency assumptions, success criteria,
and baseline results, see the detailed methodology doc:
**[docs/testing/load-testing.md](../docs/testing/load-testing.md)**.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Available Scripts](#available-scripts)
- [Environment Variables](#environment-variables)
- [Target Base URLs](#target-base-urls)
- [Smoke Test](#smoke-test)
- [Normal Load Test](#normal-load-test)
- [Failure / Resilience Test](#failure--resilience-test)
- [Saving Results](#saving-results)
- [Safety Guidelines](#safety-guidelines)
- [Detailed Documentation](#detailed-documentation)

## Prerequisites

- `bash`, `curl`, `jq`
- `uuidgen` (macOS built-in; on Linux, install `util-linux` or equivalent)
- `kcat` (`brew install kcat`) — used by `generate-traffic.sh`'s DLT phase and by
  `generate-dedup-events.sh`
- The platform running locally — see the **[Infrastructure Guide](../infra/README.md)** — with
  `auth-service`, `fraud-service`, and `ledger-service` reachable, and Kafka reachable at
  `localhost:9092`
- 3 existing, funded, `ACTIVE` accounts — one per currency (GBP/USD/EUR) — for
  `generate-traffic.sh` (pass their ids via env vars, see below)

Check tool versions:

```bash
bash --version
curl --version
jq --version
kcat -V
```

## Available Scripts

| Script                              | Purpose                                                                                                   | Default runtime |
|--------------------------------------|-------------------------------------------------------------------------------------------------------------|------------------|
| `generate-traffic.sh`                | Full demo traffic: bulk authorise/capture/reverse + idempotency replay, circuit-breaker open→hold→recover + HTTP 5xx, DLT publish, then delegates to the two scripts below | ~5–6 minutes     |
| `generate-dedup-events.sh`           | Publishes duplicate Kafka events (same `eventId`) to exercise `ledger-service`'s consumer-side dedup path   | seconds–~1 minute (rounds-dependent) |
| `generate-concurrency-conflicts.sh`  | Fires truly concurrent authorise/capture/reverse requests to exercise idempotency-race and optimistic-lock conflict paths | seconds (rounds-dependent) |

For the phase-by-phase design, the mechanics of each script, and why they're structured this way,
see [docs/testing/load-testing.md](../docs/testing/load-testing.md).

## Environment Variables

All variables have working defaults — override only what you need.

| Variable                                                     | Used by                              | Default                     |
|-----------------------------------------------------------------|-----------------------------------------|--------------------------------|
| `BASE_URL`                                                    | all scripts                            | `http://localhost:9000` (auth-service) |
| `FRAUD_BASE_URL`                                               | `generate-traffic.sh`                  | `http://localhost:9010`        |
| `LEDGER_BASE_URL`                                              | `generate-traffic.sh`, `generate-dedup-events.sh` | `http://localhost:9020` |
| `GBP_ACCOUNT_ID` / `USD_ACCOUNT_ID` / `EUR_ACCOUNT_ID`        | `generate-traffic.sh`                  | auto-provisioned if unset (where supported) |
| `ACCOUNT_ID`                                                   | `generate-concurrency-conflicts.sh`    | auto-provisions a fresh GBP account per round if unset |
| `KAFKA_BROKER`                                                 | `generate-dedup-events.sh`             | `localhost:9092`                |
| `ROUNDS`, `DUPLICATES_PER_EVENT`, `INTERVAL_SECONDS`          | `generate-dedup-events.sh`             | `10`, `3`, `1`                   |
| `ROUNDS`, `PARALLEL_REQUESTS`                                  | `generate-concurrency-conflicts.sh`    | `5`, `6`                          |
| `BULK1_COUNT`/`BULK2_COUNT`, `CB_*`, `DLT_MESSAGE_COUNT`, `DEDUP_*`, `CONCURRENCY_*`, `REST_BETWEEN_STEPS_SECONDS` | `generate-traffic.sh` phase tuning | see the script header comments |

## Target Base URLs

```bash
export BASE_URL=http://localhost:9000     # auth-service
export FRAUD_BASE_URL=http://localhost:9010
export LEDGER_BASE_URL=http://localhost:9020
```

## Smoke Test

A shrunk, fast run to confirm connectivity and basic behavior before a full run:

```bash
cd load-tests
BULK1_COUNT=5 BULK1_INTERVAL_SECONDS=1 BULK2_COUNT=5 BULK2_INTERVAL_SECONDS=1 \
CB_OPEN_REQUEST_COUNT=2 CB_HOLD_SECONDS=5 CB_RECOVERY_REQUEST_COUNT=2 \
DEDUP_ROUNDS=1 CONCURRENCY_ROUNDS=1 \
./generate-traffic.sh
```

Verify it ran cleanly:

```bash
curl -s http://localhost:9000/actuator/health | jq
```

## Normal Load Test

The full default run (all phases, ~5–6 minutes):

```bash
cd load-tests
GBP_ACCOUNT_ID=<uuid> USD_ACCOUNT_ID=<uuid> EUR_ACCOUNT_ID=<uuid> \
./generate-traffic.sh
```

## Failure / Resilience Test

`generate-traffic.sh`'s phase 2 already drives `fraud-service` into `ALWAYS_503` mode to exercise
`auth-service`'s circuit breaker. To exercise just that behavior on demand:

```bash
# force fraud-service to fail
curl -X POST http://localhost:9010/internal/test/failure-mode \
  -H "Content-Type: application/json" -d '{"mode":"ALWAYS_503"}'

# fire authorise requests here, then check circuit breaker state
curl -s http://localhost:9000/actuator/circuitbreakers | jq

# reset
curl -X POST http://localhost:9010/internal/test/failure-mode \
  -H "Content-Type: application/json" -d '{"mode":"OFF"}'
```

Or run the two focused scripts directly for dedup / concurrency-conflict behavior in isolation:

```bash
cd load-tests
./generate-dedup-events.sh
./generate-concurrency-conflicts.sh
```

## Saving Results

These are plain bash/curl scripts, not a load-testing framework — there's no built-in result-file
export. Capture output and metric snapshots manually:

```bash
mkdir -p results

# full run log
./generate-traffic.sh | tee "results/run-$(date +%Y%m%d-%H%M%S).log"

# metric snapshots
curl -s http://localhost:9000/actuator/prometheus \
  | grep -E 'auth_concurrency_conflict_total|auth_idempotency_cache_total|auth_outbox_publish' \
  > "results/auth-metrics-$(date +%Y%m%d-%H%M%S).txt"

curl -s http://localhost:9020/actuator/prometheus \
  | grep -E 'ledger_event_duplicate_skipped_total|ledger_kafka_dlt_published_total' \
  > "results/ledger-metrics-$(date +%Y%m%d-%H%M%S).txt"
```

With each result, record: commit/image version, date, environment, script parameters (rounds,
counts, intervals), and any relevant Grafana/Tempo screenshots or trace ids — see the baseline
table format in
[docs/testing/load-testing.md § Baseline Results](../docs/testing/load-testing.md#9-baseline-results).

`results/` is a local, gitignored scratch directory — do not commit generated result files.

## Safety Guidelines

- Use synthetic payment data only.
- Start with the [smoke test](#smoke-test) before a full run.
- Increase load gradually (shrink/grow the env vars above rather than jumping straight to defaults).
- Never point `BASE_URL`/`FRAUD_BASE_URL`/`LEDGER_BASE_URL` at a production environment.
- Watch CPU, memory, Postgres, and Kafka usage while a script runs (see the
  [Infrastructure Guide](../infra/README.md#observability)).
- Stop the script (`Ctrl+C`) if the environment becomes unstable — `generate-traffic.sh` always
  resets `fraud-service`'s failure mode to `OFF` on exit, even on interrupt.
- Reset local state with `docker compose ... down -v` (see the
  [Infrastructure Guide](../infra/README.md#reset-the-local-environment)) if test data needs
  cleaning up.

## Detailed Documentation

- **[Load-Testing Methodology](../docs/testing/load-testing.md)** — objectives, workload model,
  scenario definitions, concurrency assumptions, success criteria, baseline results, and
  limitations
- [Testing Strategy](../docs/testing/strategy.md)
- [Business-Critical Scenarios](../docs/testing/scenarios.md)
- [Operational Runbook](../docs/development/runbook.md) — manual `kcat` DLT/dedup reproduction steps
- [Infrastructure Guide](../infra/README.md) — starting the platform, ports, dashboards


