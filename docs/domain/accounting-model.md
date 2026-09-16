# Accounting Model

## Purpose & Scope

This document defines the business rules for how payment lifecycle events are recorded as ledger entries in this platform. It is business-rule focused, not an implementation guide — for code, see the file references at the end of each section.

The ledger is an **event-sourced, single-entry projection**: every state-changing payment event (authorise, capture, release) produces exactly one durable ledger row, keyed to the authorisation and account it belongs to. This model is a deliberate fit for this project's goal — demonstrating correct distributed transaction handling (outbox pattern, at-least-once Kafka delivery, idempotent event consumption) across cooperating services. Evolving it toward full double-entry bookkeeping is a natural next step and is tracked as a single roadmap item (see [Related](#related)); the rest of this document describes what is built and working today.

### What is journaled

| Event | Journaled? | Notes |
|---|---|---|
| Authorise (approved) | Yes | Funds reserved; projected as `AUTHORISATION_AUTHORISED` |
| Authorise (declined) | No | No funds were moved, so there is nothing to record ([ADR 0002](../decisions/0002-full-capture-only.md)) |
| Capture (full) | Yes | Reserved funds settle; projected as `AUTHORISATION_CAPTURED` |
| Release / Reverse | Yes | Reserved funds return to available balance; projected as `AUTHORISATION_REVERSED` |
| Refund (post-capture) | Out of scope for MVP | See [`payment-lifecycle.md`](../flows/payment-lifecycle.md) |

## 1) Ledger Model

### Accounts

Each customer/merchant `account` (in `auth-service`) tracks two balances that together represent its ledger position:

| Balance | Meaning |
|---|---|
| `availableBalance` | Funds free to reserve against a new authorisation |
| `reservedBalance` | Funds currently held against one or more open (`AUTHORISED`) authorisations |

Every posting moves value between these two balances (or, for capture, permanently settles it out of `reservedBalance`). This two-balance model cleanly represents authorise/capture/release for a single-party account ledger, which is the scope of this platform.

### Entry structure

Each ledger entry (`ledger_entry` row, `ledger-service`) carries:

| Field | Description |
|---|---|
| `eventId` | Unique identifier of the business event this entry represents (also the idempotency key for projection) |
| `aggregateType` / `aggregateId` | Which domain aggregate the event belongs to (authorisation) |
| `accountId` / `authorisationId` | The account and authorisation this entry is posted against |
| `eventType` | `AUTHORISATION_AUTHORISED`, `AUTHORISATION_CAPTURED`, or `AUTHORISATION_REVERSED` |
| `amount` | The monetary amount affected by this event |
| `currencyCode` | ISO 4217 currency code for the amount |
| `merchantReference` | Merchant-supplied reference carried through from the originating request |
| `idempotencyKey` | The idempotency key used on the originating authorisation-service request |
| `occurredAt` | When the underlying business event occurred |
| `createdAt` | When the ledger row was written |

A companion `ledger_event_log` table stores the raw event payload for every event received (including declines), giving a complete audit trail independent of the business projection.

## 2) Core Invariants

These are the business rules every posting satisfies:

1. **Total reserved + available balance is conserved per account.** Authorise moves value from `availableBalance` to `reservedBalance`; reverse moves it back; capture settles it out of `reservedBalance` permanently. No event silently creates or destroys value on an account.

2. **No posting on invalid state transitions.** A ledger entry is only created for an event that represents a legal transition of the authorisation state machine (see [`state-machine.md`](./state-machine.md)). This is guaranteed upstream: `auth-service` only writes an outbox event when the underlying state transition itself succeeds, in the same DB transaction ([ADR 0001](../decisions/0001-use-kafka-outbox.md), [ADR 0005](../decisions/0005-idempotency-and-concurrency.md)).

3. **Currency consistency per posting.** An authorisation, its eventual capture, and any release all carry the same currency the authorisation was created with. This is enforced at authorisation time — `Account.reserve(...)` rejects a currency that does not match the account's currency (`CurrencyMismatchException`) before any reservation is attempted.

4. **Idempotent posting by business key.** Replaying the same business event (Kafka's at-least-once delivery) never creates a second ledger entry. `ledger-service` performs an atomic `INSERT ... ON CONFLICT DO NOTHING` into `processed_event` keyed on `eventId` before projecting; a duplicate `eventId` is skipped and counted (`ledger_event_duplicate_skipped_total`).

## 3) Posting Rules by Event

| Event | Ledger effect |
|---|---|
| **Authorise (approved)** | One `ledger_entry` row, `eventType=AUTHORISATION_AUTHORISED`, `amount=<authorised amount>`. Account-side: `availableBalance -= amount`, `reservedBalance += amount`. |
| **Capture (full)** | One `ledger_entry` row, `eventType=AUTHORISATION_CAPTURED`, `amount=<captured amount>`. Account-side: `reservedBalance -= amount` (funds are settled, not returned to `availableBalance`). This platform supports full capture of the authorised amount, after which the authorisation becomes terminal. |
| **Release / Void (reverse)** | One `ledger_entry` row, `eventType=AUTHORISATION_REVERSED`, `amount=<released amount>`. Account-side: `reservedBalance -= amount`, `availableBalance += amount` (funds return, since they were only earmarked, never actually debited). |
| **Decline** | No `ledger_entry` row. No funds were ever moved, so there is nothing to journal — `AUTHORISATION_DECLINED` events are received and logged in `ledger_event_log` for audit purposes but intentionally produce no business projection. |

## 4) Worked Examples

### Example A — Authorise then fully capture

| Step | Available balance | Reserved balance | Authorisation status |
|---|---:|---:|---|
| Start | 500.00 | 0.00 | — |
| Authorise 100.00 | 400.00 | 100.00 | `AUTHORISED` |
| Capture 100.00 (full) | 400.00 | 0.00 | `CAPTURED` (terminal) |

Net effect: 100.00 has settled out of the account's total position, and the authorisation is now terminal.

### Example B — Authorise then reverse (release)

| Step | Available balance | Reserved balance | Authorisation status |
|---|---:|---:|---|
| Start | 500.00 | 0.00 | — |
| Authorise 100.00 | 400.00 | 100.00 | `AUTHORISED` |
| Reverse 100.00 | 500.00 | 0.00 | `REVERSED` (terminal) |

Net effect: the account returns to its starting position — the 100.00 was only ever earmarked, never actually spent.

### Example C — Authorise then decline (for comparison)

A decline is reached directly instead of `AUTHORISED`, so there is no reservation to release:

| Step | Available balance | Reserved balance | Authorisation status |
|---|---:|---:|---|
| Start | 500.00 | 0.00 | — |
| Authorise 100.00 (insufficient funds / fraud decline) | 500.00 | 0.00 | `DECLINED` (terminal) |

## 5) Reconciliation Notes

How ledger projections reconcile with payment state and outbox events:

- Every state-changing authorisation event (`AUTHORISATION_AUTHORISED`, `AUTHORISATION_CAPTURED`, `AUTHORISATION_REVERSED`) is written to the `auth-service` outbox in the same DB transaction as the state change itself, guaranteeing the event exists if and only if the state transition committed (see [ADR 0001](../decisions/0001-use-kafka-outbox.md)).
- `ledger-service` consumes these events from `auth.events` and writes two things per event: a raw audit copy (`ledger_event_log`) and a business projection (`ledger_entry`). Reconciliation between "what auth-service says happened" and "what the ledger recorded" is done by comparing `authorisation_event` rows in `auth-service` against `ledger_entry` rows keyed by `eventId`/`authorisationId` in `ledger-service`.
- A one-to-one match is expected for every `AUTHORISED`/`CAPTURED`/`REVERSED` event; `DECLINED` events are expected to have **no** corresponding `ledger_entry` row by design (see section 3 above).
- Gaps (an auth-service event with no matching ledger entry after a reasonable delay) indicate either outbox publish lag/backlog or a stuck/failed consumer — see [`runbook.md`](../observability/runbook.md) for detection and mitigation.
- Unrecognized event types are routed to the dead-letter topic (`auth.events.ledger.dlt`) rather than silently dropped, so a reconciliation gap is always traceable to either a backlog, a DLT entry, or a genuine bug — never a silent loss (see [ADR 0004](../decisions/0004-use-event-and-dlt-topics.md)).

## Related

- [`state-machine.md`](./state-machine.md) — authorisation state machine that governs which events are legal to journal
- [`../decisions/0001-use-kafka-outbox.md`](../decisions/0001-use-kafka-outbox.md) — outbox pattern guaranteeing event/state consistency
- [`../decisions/0002-full-capture-only.md`](../decisions/0002-full-capture-only.md) — full-capture-only MVP scope decision
- [`../decisions/0003-ledger-raw-and-normalized-events.md`](../decisions/0003-ledger-raw-and-normalized-events.md) — raw event log vs. normalized projection
- [`../data/ledger-er-diagram.mmd`](../data/ledger-er-diagram.mmd) — ledger schema
- [`../roadmap.md`](../roadmap.md) — planned double-entry adoption, partial capture, refunds, fee accounting


