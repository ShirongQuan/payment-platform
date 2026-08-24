package org.example.ledger.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A single ledger event entry shown in an account's event timeline. */
public record AccountEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    OffsetDateTime occurredAt,
    BigDecimal amount,
    String currencyCode) {}
