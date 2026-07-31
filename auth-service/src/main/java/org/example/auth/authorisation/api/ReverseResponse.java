package org.example.auth.authorisation.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationStatus;

public record ReverseResponse(
    UUID authorisationId,
    String idempotencyKey,
    BigDecimal reversedAmount,
    String currencyCode,
    AuthorisationStatus status,
    String reasonCode,
    OffsetDateTime updatedAt) {}
