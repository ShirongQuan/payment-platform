package org.example.auth.outbox.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationStatus;

public record AuthorisationAuthorisedPayload(
    UUID authorisationId,
    UUID accountId,
    BigDecimal amount,
    String currencyCode,
    String idempotencyKey,
    AuthorisationStatus status,
    OffsetDateTime createdAt,
    String merchantReference) {}
