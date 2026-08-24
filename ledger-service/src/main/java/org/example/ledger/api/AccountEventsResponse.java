package org.example.ledger.api;

import java.util.List;
import java.util.UUID;

/** Response body for the account events timeline endpoint, ordered most-recent-first. */
public record AccountEventsResponse(UUID accountId, List<AccountEvent> events) {}
