package org.example.ledger.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuthorisationCapturedPayload(
    UUID authorisationId,
    UUID accountId,
    BigDecimal amount,
    String currencyCode,
    String idempotencyKey,
    String status,
    OffsetDateTime capturedAt) {}
