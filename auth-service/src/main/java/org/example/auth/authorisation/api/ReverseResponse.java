package org.example.auth.authorisation.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;

/** Response body describing the outcome of a reverse request. */
public record ReverseResponse(
    UUID authorisationId,
    String idempotencyKey,
    BigDecimal reversedAmount,
    String currencyCode,
    AuthorisationStatus status,
    AuthorisationEventReason reasonCode,
    OffsetDateTime updatedAt) {}
