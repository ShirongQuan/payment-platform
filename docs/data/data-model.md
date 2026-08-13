# Data Model Overview

This document summarizes the core tables used by `auth-service` and `ledger-service`.

## ER Diagrams

- Auth DB: [`auth-er-diagram.mmd`](auth-er-diagram.mmd)
- Ledger DB: [`ledger-er-diagram.mmd`](ledger-er-diagram.mmd)

## Auth DB (auth-service)

### Main tables

- `account`
    - Source of truth for account balances and account status.
    - `available_balance` and `reserved_balance` are updated by authorise/capture flows.

- `authorisation`
    - Stores current business state of each authorisation (`AUTHORISED`, `CAPTURED`, `REVERSED`, `DECLINED`).
    - Linked to `account` via `account_id`.

- `authorisation_event`
    - Durable domain-event history for authorisation lifecycle transitions.
    - Tracks `event_type`, `idempotency_key`, `reason_code`, and `correlation_id`.

### Event/outbox table purpose

- `outbox_event`
    - Transactional outbox for reliable asynchronous delivery to Kafka.
    - Event is written in the same transaction as business changes, then published by scheduler.
    - Includes retry state (`status`, `retry_count`, `next_attempt_at`, `last_error`) and claim lease fields (
      `claimed_at`, `claim_until`).

### Idempotency strategy

- Request-level idempotency is enforced by checking existing `authorisation` rows and validating payload equality.
- Event-level idempotency in `authorisation_event` is enforced with:
    - `uq_authorisation_event_account_eventtype_idempotency` on `(account_id, event_type, idempotency_key)`.
- Additional uniqueness `(account_id, idempotency_key, created_at)` supports replay/audit without globally locking the
  key forever.

## Ledger DB (ledger-service)

### Main tables

- `ledger_entry`
    - Read-optimized projection used by query APIs.
    - Stores the current immutable accounting entries derived from consumed events.

- `ledger_event_log`
    - Immutable event audit log of consumed Kafka events.
    - Preserves original payload and correlation metadata for tracing/debugging.

### Idempotency strategy

- `processed_event`
    - Consumer-side deduplication table keyed by `event_id`.
    - Insert-once guard (`on conflict do nothing`) ensures each event id is projected at most once.
- Handler flow:
    - First insert into `processed_event` succeeds -> persist into `ledger_event_log` and `ledger_entry`.
    - Duplicate insert -> skip event processing safely.

## End-to-end event reliability

- Producer side: transactional outbox in auth DB guarantees events are not lost between DB commit and publish.
- Consumer side: `processed_event` table in ledger DB guarantees idempotent projection under retries/rebalances.

