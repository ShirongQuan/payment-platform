package org.example.auth.fraud;

import java.math.BigDecimal;
import java.util.UUID;

/** Outbound request body sent to the external fraud-service {@code /fraud/check} endpoint. */
public record FraudCheckRequest(
    UUID accountId,
    String idempotencyKey,
    BigDecimal amount,
    String currencyCode,
    String merchantReference,
    String ipAddress) {}
