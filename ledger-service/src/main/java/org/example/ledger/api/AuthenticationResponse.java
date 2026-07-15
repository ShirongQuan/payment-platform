package org.example.ledger.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuthenticationResponse(
    UUID authorisationId,
    UUID accountId,
    String merchantReference,
    BigDecimal amount,
    String currencyCode,
    String status,
    OffsetDateTime createdAt,
    UUID sourceEventId) {}
