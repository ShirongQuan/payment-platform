package org.example.authservice.authorisation.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.authservice.authorisation.AuthorisationStatus;

public record AuthorisationResponse(
    UUID id,
    UUID accountId,
    String idempotencyKey,
    BigDecimal amount,
    String currencyCode,
    String merchantReference,
    AuthorisationStatus status,
    String failureReason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
