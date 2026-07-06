package org.example.authservice.outbox;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.authservice.authorisation.domain.AuthorisationStatus;

public record AuthorisationCreatedPayload(
    UUID id,
    UUID accountId,
    BigDecimal amount,
    String currencyCode,
    AuthorisationStatus status,
    String failureReason,
    OffsetDateTime createdAt) {}
