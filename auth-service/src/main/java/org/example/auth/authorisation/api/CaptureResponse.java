package org.example.auth.authorisation.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationStatus;

/** Response body describing the outcome of a capture request. */
public record CaptureResponse(
    UUID authorisationId,
    String idempotencyKey,
    BigDecimal capturedAmount,
    String currencyCode,
    AuthorisationStatus status,
    OffsetDateTime updatedAt) {}
