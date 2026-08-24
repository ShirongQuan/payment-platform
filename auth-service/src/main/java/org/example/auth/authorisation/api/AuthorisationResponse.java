package org.example.auth.authorisation.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationStatus;

/** Response body describing the current state of an authorisation. */
public record AuthorisationResponse(
    UUID id,
    UUID accountId,
    String idempotencyKey,
    BigDecimal amount,
    String currencyCode,
    String merchantReference,
    AuthorisationStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
