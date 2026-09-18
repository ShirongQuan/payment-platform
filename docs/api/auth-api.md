# Auth Service API

## Table of Contents

- [Shared Error Format](#shared-error-format)
- [POST `/accounts`](#post-accounts)
- [GET `/accounts/{accountId}`](#get-accountsaccountid)
- [POST `/accounts/{accountId}/deposits`](#post-accountsaccountiddeposits)
- [POST `/authorisations`](#post-authorisations)
- [GET `/authorisations/{authorisationId}`](#get-authorisationsauthorisationid)
- [POST `/authorisations/{authorisationId}/captures`](#post-authorisationsauthorisationidcaptures)
- [POST `/authorisations/{authorisationId}/reversals`](#post-authorisationsauthorisationidreversals)

Base path: `/` (server port `9000`)

This document covers the important auth-service endpoints and the expected behavior for:

- request
- response
- status transitions
- error cases
- idempotency behavior

## Shared Error Format

Domain errors are returned as RFC 7807 `application/problem+json` from `AuthExceptionHandler`.

Typical fields:

- `title`
- `status`
- `detail`
- `instance`
- `errorCode`
- endpoint-specific fields such as `accountId`, `authorisationId`, `currencyCode`

Bean validation failures (for example invalid DTO shape) currently return HTTP `400` with an empty body in controller
tests.

---

## POST `/accounts`

Creates a new account.

### Request

```json
{
  "currencyCode": "GBP"
}
```

Constraints:

- `currencyCode`: required, exactly 3 letters, valid ISO currency code

### Response

HTTP `200 OK`

```json
{
  "accountId": "uuid",
  "status": "ACTIVE",
  "currencyCode": "GBP",
  "availableBalance": 0,
  "reservedBalance": 0,
  "createdAt": "2026-07-31T10:00:00Z",
  "updatedAt": "2026-07-31T10:00:00Z"
}
```

### Status transitions

- Account lifecycle status is initialized (typically `ACTIVE` on creation).
- No authorisation status change applies.

### Error cases

- `400 Bad Request`: invalid currency code (`INVALID_CURRENCY`)

### Idempotency behavior

- No idempotency key support.
- Repeating the same request creates additional accounts.

---

## GET `/accounts/{accountId}`

Fetches account details by id.

### Request

Path parameter:

- `accountId` (UUID)

### Response

HTTP `200 OK`

```json
{
  "accountId": "uuid",
  "status": "ACTIVE",
  "currencyCode": "GBP",
  "availableBalance": 100.00,
  "reservedBalance": 20.00,
  "createdAt": "2026-07-31T10:00:00Z",
  "updatedAt": "2026-07-31T11:00:00Z"
}
```

### Status transitions

- Read-only endpoint; no state transition.

### Error cases

- `404 Not Found`: account does not exist (`ACCOUNT_NOT_FOUND`)

### Idempotency behavior

- Naturally idempotent read.

---

## POST `/accounts/{accountId}/deposits`

Deposits funds to account available balance.

### Request

```json
{
  "amount": 50.00,
  "currencyCode": "GBP"
}
```

Constraints:

- `amount`: required, minimum `0.01`
- `currencyCode`: required, valid ISO currency code

### Response

HTTP `200 OK` with updated account snapshot:

```json
{
  "accountId": "uuid",
  "status": "ACTIVE",
  "currencyCode": "GBP",
  "availableBalance": 150.00,
  "reservedBalance": 20.00,
  "createdAt": "2026-07-31T10:00:00Z",
  "updatedAt": "2026-07-31T11:30:00Z"
}
```

### Status transitions

- Account status does not change.
- Balance changes: `availableBalance += amount`.

### Error cases

- `400 Bad Request`: invalid amount/validation failure
- `400 Bad Request`: currency mismatch (`CURRENCY_MISMATCH`)
- `400 Bad Request`: invalid currency code (`INVALID_CURRENCY`)
- `404 Not Found`: account does not exist (`ACCOUNT_NOT_FOUND`)

### Idempotency behavior

- No idempotency key support.
- Retrying the same request applies deposit again.

---

## POST `/authorisations`

Creates an authorisation request for reserve/capture workflow.

### Request

```json
{
  "accountId": "uuid",
  "idempotencyKey": "auth-001",
  "amount": 10.00,
  "currencyCode": "GBP",
  "merchantReference": "order-123"
}
```

Constraints:

- `accountId`: required UUID
- `idempotencyKey`: required, max 20 chars
- `amount`: required, minimum `0.01`
- `currencyCode`: required, exactly 3 chars, valid ISO currency code
- `merchantReference`: required, max 128 chars

### Response

HTTP `200 OK`

```json
{
  "id": "uuid",
  "accountId": "uuid",
  "idempotencyKey": "auth-001",
  "amount": 10.00,
  "currencyCode": "GBP",
  "merchantReference": "order-123",
  "status": "AUTHORISED",
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:00:00Z"
}
```

If available balance is insufficient, or the request is declined by the synchronous fraud pre-check
(`POST /fraud/check` on fraud-service), the request still succeeds at the HTTP level and returns
`status: DECLINED` (same response shape).

### Fraud pre-check

- Before reserving funds, auth-service calls fraud-service's `POST /fraud/check` synchronously
  (see [Fraud API](./fraud-api.md)), guarded by a resilience4j circuit breaker + 250ms time limiter.
- If fraud-service is unavailable/times out/circuit is open: request is declined
  (`FRAUD_SERVICE_UNAVAILABLE`, risk score `100`) unless a fail-open policy applies. Fail-open is
  disabled by default; when enabled it only allows a configured allow-list of trusted `accountId`s at
  or below a configured max amount to proceed as `APPROVED` (risk score `40`,
  reason `FRAUD_UNAVAILABLE_TRUSTED_TINY_AMOUNT`).
- If fraud-service recommends locking the account (high risk score or repeated-decline pattern) and the
  account is currently `ACTIVE`, auth-service locks the account as part of the same transaction.

### Status transitions

- New authorisation is persisted as one of:
    - `AUTHORISED` (funds reserved)
    - `DECLINED` (insufficient funds, fraud decline, or fraud-service unavailable without fail-open)

### Error cases

- `400 Bad Request`: invalid request payload/validation
- `400 Bad Request`: currency mismatch (`CURRENCY_MISMATCH`)
- `400 Bad Request`: invalid currency code (`INVALID_CURRENCY`)
- `404 Not Found`: account does not exist (`ACCOUNT_NOT_FOUND`)
- `409 Conflict`: idempotency key reused with different request payload (`IDEMPOTENCY_CONFLICT`)

### Idempotency behavior

- Scoped by `(accountId, idempotencyKey)`.
- If same key is retried with same `amount`, `currencyCode`, and `merchantReference`, existing authorisation is
  returned.
- If same key is retried with different semantic payload, returns `409 IDEMPOTENCY_CONFLICT`.
- Concurrent same-key races are resolved by persisting one record and replaying it for the loser request.

---

## GET `/authorisations/{authorisationId}`

Fetches one authorisation by id.

### Request

Path parameter:

- `authorisationId` (UUID)

### Response

HTTP `200 OK`

```json
{
  "id": "uuid",
  "accountId": "uuid",
  "idempotencyKey": null,
  "amount": 10.00,
  "currencyCode": "GBP",
  "merchantReference": "order-123",
  "status": "AUTHORISED",
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:00:00Z"
}
```

Note: current implementation returns `idempotencyKey: null` on lookup by id.

### Status transitions

- Read-only endpoint; no state transition.

### Error cases

- `404 Not Found`: authorisation does not exist (`AUTHORISATION_NOT_FOUND`)

### Idempotency behavior

- Naturally idempotent read.

---

## POST `/authorisations/{authorisationId}/captures`

Captures a previously authorised transaction.

### Request

```json
{
  "idempotencyKey": "capture-001"
}
```

Constraints:

- `idempotencyKey`: required, max 20 chars

### Response

HTTP `200 OK`

```json
{
  "authorisationId": "uuid",
  "idempotencyKey": "capture-001",
  "capturedAmount": 10.00,
  "currencyCode": "GBP",
  "status": "CAPTURED",
  "updatedAt": "2026-07-31T12:30:00Z"
}
```

### Status transitions

- `AUTHORISED -> CAPTURED`
- If already `CAPTURED`, replay is possible when idempotency key matches previous capture event.

### Error cases

- `400 Bad Request`: invalid request payload/validation
- `404 Not Found`: authorisation not found (`AUTHORISATION_NOT_FOUND`)
- `409 Conflict`: idempotency conflict (`IDEMPOTENCY_CONFLICT`)
- `409 Conflict`: invalid state of authorisation (`INVALID_AUTHORISATION_STATE`)

### Idempotency behavior

- If already captured and the same capture idempotency key is retried, service returns previous capture response.
- If already captured but different idempotency key is provided, returns `409 INVALID_AUTHORISATION_STATE`.
- Concurrent same-key races are handled by retrying lookup after transaction rollback.

---

## POST `/authorisations/{authorisationId}/reversals`

Reverses (releases) a previously authorised reservation, returning the reserved amount from
`reservedBalance` back to `availableBalance`.

### Request

```json
{
  "idempotencyKey": "reverse-001",
  "reasonCode": "CUSTOMER_REQUEST"
}
```

Constraints:

- `idempotencyKey`: required, max 20 chars
- `reasonCode`: required, max 20 chars
- `reasonCode` must be one of `AuthorisationEventReason`: `NONE`, `INSUFFICIENT_FUNDS`, `ACCOUNT_LOCKED`,
  `ACCOUNT_INACTIVE`, `CURRENCY_MISMATCH`, `INVALID_AMOUNT`, `INVALID_STATE`, `AUTHORISATION_NOT_FOUND`,
  `DUPLICATE_REQUEST`, `CUSTOMER_REQUEST`, `MERCHANT_REQUEST`, `EXPIRED`, `TIMEOUT`, `SYSTEM_ERROR`

### Response

HTTP `200 OK`

```json
{
  "authorisationId": "uuid",
  "idempotencyKey": "reverse-001",
  "reversedAmount": 10.00,
  "currencyCode": "GBP",
  "status": "REVERSED",
  "reasonCode": "CUSTOMER_REQUEST",
  "updatedAt": "2026-07-31T13:00:00Z"
}
```

### Status transitions

- `AUTHORISED -> REVERSED`: reserved amount is released, `account.reservedBalance -= amount`
  (`availableBalance` is unaffected since the funds were never debited from it).
- If already `REVERSED`, replay is possible when the idempotency key matches the previous reverse event.

### Error cases

- `400 Bad Request`: invalid request payload/validation
- `404 Not Found`: authorisation not found (`AUTHORISATION_NOT_FOUND`)
- `409 Conflict`: idempotency key conflict — already reversed with a different idempotency key, or same
  key retried with a different `reasonCode` (`IDEMPOTENCY_CONFLICT`)
- `409 Conflict`: invalid state of authorisation — reverse attempted on an authorisation that is not
  `AUTHORISED` (e.g. `DECLINED` or `CAPTURED`) (`INVALID_AUTHORISATION_STATE`)

### Idempotency behavior

- Scoped by `(authorisationId, idempotencyKey)`, fingerprinted on `(authorisationId, reasonCode)`.
- If already reversed and the same reverse idempotency key is retried, the service returns the previous
  reverse response.
- If already reversed but a different idempotency key is provided, returns `409 IDEMPOTENCY_CONFLICT`.
- Concurrent same-key races are handled by retrying lookup after transaction rollback
  (`ConcurrentIdempotencyRaceException`), same pattern as capture.
- A concurrent reverse/capture racing on the account's optimistic-lock version raises
  `AccountConcurrencyConflictException`, surfaced to the caller as a `409` for the losing request.

