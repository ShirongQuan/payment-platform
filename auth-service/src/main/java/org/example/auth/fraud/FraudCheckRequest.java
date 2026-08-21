package org.example.auth.fraud;

import java.math.BigDecimal;
import java.util.UUID;

public record FraudCheckRequest(
    UUID accountId,
    String idempotencyKey,
    BigDecimal amount,
    String currencyCode,
    String merchantReference,
    String ipAddress) {}
