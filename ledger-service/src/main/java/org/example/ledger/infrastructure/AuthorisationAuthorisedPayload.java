package org.example.ledger.infrastructure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuthorisationAuthorisedPayload(
    UUID authorisationId,
    UUID accountId,
    String idempotencyKey,
    String merchantReference,
    BigDecimal amount,
    String currencyCode,
    String status,
    String failureReason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
