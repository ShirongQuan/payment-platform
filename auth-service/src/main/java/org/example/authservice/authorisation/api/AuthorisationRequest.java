package org.example.authservice.authorisation.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.example.authservice.common.validation.ValidCurrencyCode;

public record AuthorisationRequest(
    @NotNull UUID accountId,
    @NotBlank @Size(max = 20) String idempotencyKey,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @ValidCurrencyCode @Size(max = 3) String currencyCode,
    String merchantReference) {}
