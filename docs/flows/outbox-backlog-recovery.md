# Outbox Backlog Recovery

## Purpose

Explain how to detect, triage, and recover from a growing auth-service outbox backlog or a
terminally `FAILED` outbox row. Kafka publish failures don't disappear silently — they either
retry automatically on a backoff schedule, or land in a terminal state that requires operator
action.

## Background

- Outbox event lifecycle (`OutboxEventStatus`): `NEW -> PUBLISHING -> PUBLISHED` (happy path), or
  `NEW -> PUBLISHING -> NEW` (retry, repeated), or terminally `NEW -> PUBLISHING -> FAILED`.
- `OutboxBackoffPolicy`: retry delay is `10s` (attempt 1), `30s` (attempt 2), `60s` (attempt 3),
  `300s` (attempt 4+); a row is marked `FAILED` once `nextRetryCount >= 5` (i.e. after 5 failed
  publish attempts).
- Claim lease (`outbox.publisher.claim-lease`, default `30s`): if a claimed `PUBLISHING` row's
  lease expires before the publish attempt completes (e.g. the instance holding the claim
  crashed/stalled), the next `OutboxScheduler` cycle automatically reclaims it back to `NEW` via
  `reclaimStalePublishing()` — no operator action needed for this case.
- `auth_outbox_backlog` (gauge) only counts `NEW` + `PUBLISHING` rows. A `FAILED` row drops out of
  this gauge but is **not** recovered — it becomes invisible in the backlog metric unless you also
  watch `auth_outbox_publish_attempts_total{result="failed"}` or query the outbox table directly.
- There is **no dedicated dead-letter queue/topic for producer-side (outbox) failures** in this
  project today. "DLQ" in this context means the `FAILED` row sitting in the outbox table, and
  "manual replay" means an operator resets it back to `NEW`. This is a different concept from
  ledger-service's *consumer-side* Kafka DLT topic (`auth.events.ledger.dlt`), which handles
  Kafka-consumption failures, not publish failures — see `event-consuming.mmd`.

## Detect

- **Dashboard/alerting signal**: `auth_outbox_backlog` trending up (NEW/PUBLISHING rows not
  draining), or `auth_outbox_publish_attempts_total{result="failed"}` incrementing, or
  `auth_outbox_publish_lag_seconds` (creation → successful publish) growing past its normal range
  (see the outbox panels in `infra/grafana/dashboards/payment-platform-overview.json`).
- **Direct query** (source of truth, since `FAILED` rows are invisible to the backlog gauge):
  ```sql
  select status, count(*), min(created_at), max(retry_count)
  from outbox_event
  group by status;
  ```

## Triage

For any row not progressing to `PUBLISHED`, inspect `status`, `retry_count`, `last_error`, and
`next_attempt_at`:

| status       | condition                          | meaning                                                     |
|--------------|-------------------------------------|--------------------------------------------------------------|
| `PUBLISHING` | `claim_until < now`                 | stale claim — will self-heal on the next scheduler cycle    |
| `NEW`        | `next_attempt_at` in the future     | waiting on backoff — normal, no action needed                |
| `NEW`        | `next_attempt_at` far in the past   | scheduler not running, or batch size too small for backlog   |
| `FAILED`     | `retry_count >= 5`                  | exhausted automatic retries — needs a triage decision below  |

For `FAILED` rows, read `last_error` (truncated to 100 chars) and correlate by `event_id` /
`correlation_id` with logs and traces (Tempo) to find the root cause, typically one of:

- **Transient infra issue** (Kafka broker unavailable, network blip during the retry window) —
  the underlying cause has likely since resolved itself.
- **Bad data / non-retryable payload issue** (e.g. serialization failure) — retrying as-is will
  fail again; needs a code or data fix first.

## Retry (automatic)

This is the default, no-operator-action path and handles the vast majority of transient failures:

1. `OutboxScheduler` polls every `outbox.publisher.delay-ms` (default `10s`).
2. Stale `PUBLISHING` claims (past `claim_until`) are reclaimed back to `NEW`.
3. Due `NEW` rows (`next_attempt_at <= now`) are claimed and republished.
4. A failed publish attempt increments `retry_count` and reschedules `next_attempt_at` per
   `OutboxBackoffPolicy`, until the 5th failure marks the row `FAILED`.

## DLQ / Manual replay

Once a row is `FAILED`, it is terminal — the scheduler will never pick it up again. Recovery today
is a **manual operation**:

1. Confirm the root cause is resolved (or fixed) per Triage above.
2. Manually requeue the row:
   ```sql
   update outbox_event
   set status = 'NEW', retry_count = 0, next_attempt_at = now(), last_error = null
   where id = :eventId;
   ```
3. The row re-enters the normal scheduler loop on the next poll cycle and is retried like any
   other `NEW` event.
4. If the root cause cannot be fixed (e.g. permanently invalid payload), leave the row `FAILED`
   for audit/investigation rather than requeuing it blindly.

See the decision flow in
[`outbox-backlog-recovery-flow.mmd`](./outbox-backlog-recovery-flow.mmd) for the full
detect → triage → retry → manual-replay path.

## Metrics & dashboards

- `auth_outbox_backlog` — gauge, live count of `NEW`/`PUBLISHING` rows.
- `auth_outbox_publish_lag_seconds` — histogram, time from event creation to successful publish.
- `auth_outbox_publish_attempts_total{result="success"|"retry"|"failed"}` — counter, per-attempt
  outcome.
- Grafana: `payment-platform-overview.json` (`infra/grafana/dashboards/`).

## Known MVP limitation / suggested improvement

- No admin endpoint or scheduled job exists to auto-requeue `FAILED` rows — recovery today is a
  manual SQL update, as described above.
- Suggested future improvement: an ops endpoint (or a scheduled "cool-down retry" job) to requeue
  `FAILED` rows after an operator decision, and/or a genuine dead-letter table/topic that preserves
  full context (payload, error history, all retry attempts) for audit instead of overwriting
  `retry_count`/`status` in place.

## Related

- [`outbox-backlog-recovery-flow.mmd`](./outbox-backlog-recovery-flow.mmd) — decision-logic
  flowchart for this doc.
- [`event-publishing-sequence.mmd`](./event-publishing-sequence.mmd) — sequence diagram for the normal
  publish/retry/fail path.
- [`failure-scenarios.md`](./failure-scenarios.md) — scenario 3 (outbox publish transient
  failure).
- `docs/roadmap.md` — Reliability patterns (outbox lag/backlog metrics).

