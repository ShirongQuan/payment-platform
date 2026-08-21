package org.example.fraud.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.example.shared.currency.ValidCurrencyCode;

public record FraudCheckRequest(
    @NotNull UUID accountId,
    @NotBlank String idempotencyKey,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currencyCode,
    @NotBlank String merchantReference,
    @NotBlank String ipAddress) {}
