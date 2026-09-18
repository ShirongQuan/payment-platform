# Ledger Service API

## Table of Contents

- [GET `/authorisations/{authorisationId}`](#get-authorisationsauthorisationid)
- [GET `/accounts/{accountId}/events`](#get-accountsaccountidevents)

Base path: `/` (server port `9020`)

This document covers the important ledger-service endpoints and the expected behavior for:
- request
- response
- status transitions
- error cases
- idempotency behavior

The ledger service endpoints are read-only projection queries.

---

## GET `/authorisations/{authorisationId}`

Returns ledger entries linked to an authorisation.

### Request
Path parameter:
- `authorisationId` (UUID)

### Response
HTTP `200 OK`

```json
[
  {
	"authorisationId": "uuid",
	"accountId": "uuid",
	"merchantReference": "order-123",
	"amount": 10.00,
	"currencyCode": "GBP",
	"status": "AUTHORISED",
	"createdAt": "2026-07-31T12:00:00Z",
	"sourceEventId": "uuid"
  }
]
```

Notes:
- Response type is a list; empty list is returned when nothing matches.

### Status transitions
- Read-only endpoint; no transition is executed by this service.
- Returned `status` values reflect previously persisted authorisation states.

### Error cases
- `400 Bad Request`: malformed UUID path variable
- `500 Internal Server Error`: unexpected server/database errors

### Idempotency behavior
- Naturally idempotent read.

---

## GET `/accounts/{accountId}/events`

Returns account event timeline from ledger projection.

### Request
Path parameter:
- `accountId` (UUID)

### Response
HTTP `200 OK`

```json
{
  "accountId": "uuid",
  "events": [
	{
	  "eventId": "uuid",
	  "eventType": "AUTHORISATION_AUTHORISED",
	  "aggregateId": "uuid",
	  "occurredAt": "2026-07-31T12:00:00Z",
	  "amount": 10.00,
	  "currencyCode": "GBP"
	}
  ]
}
```

Notes:
- If no events exist, `events` is an empty list.

### Status transitions
- Read-only endpoint; no transition is executed by this service.

### Error cases
- `400 Bad Request`: malformed UUID path variable
- `500 Internal Server Error`: unexpected server/database errors

### Idempotency behavior
- Naturally idempotent read.

