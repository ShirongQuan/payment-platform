package org.example.auth.outbox.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationStatus;

public record AuthorisationCreatedPayload(
    UUID id,
    UUID accountId,
    BigDecimal amount,
    String currencyCode,
    AuthorisationStatus status,
    String failureReason,
    OffsetDateTime createdAt) {}
