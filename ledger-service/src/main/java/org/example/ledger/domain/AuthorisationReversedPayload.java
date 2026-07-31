package org.example.ledger.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuthorisationReversedPayload(
    UUID authorisationId,
    UUID accountId,
    BigDecimal amount,
    String currencyCode,
    String idempotencyKey,
    String status,
    OffsetDateTime reversedAt,
    String reasonCode) {}
