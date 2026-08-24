package org.example.ledger.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Deserialized payload of an {@link EventType#AUTHORISATION_AUTHORISED} event. */
public record AuthorisationAuthorisedPayload(
    UUID authorisationId,
    UUID accountId,
    String idempotencyKey,
    String merchantReference,
    BigDecimal amount,
    String currencyCode,
    String status,
    OffsetDateTime createdAt) {}
