package org.example.fraud.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record FraudCheckRequest(
    @NotNull UUID accountId,
    @NotBlank String idempotencyKey,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    // TODO: move the currency validation logic to shared lib and validate the currency here
    @NotBlank String currencyCode,
    @NotBlank String merchantReference,
    @NotBlank String ipAddress,
    @NotNull UUID correlationId) {}
