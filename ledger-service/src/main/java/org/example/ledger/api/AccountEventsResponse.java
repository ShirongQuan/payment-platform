package org.example.ledger.api;

import java.util.List;
import java.util.UUID;

public record AccountEventsResponse(UUID accountId, List<AccountEvent> events) {}
