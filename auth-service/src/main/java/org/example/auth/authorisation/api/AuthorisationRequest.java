package org.example.auth.authorisation.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.example.shared.currency.ValidCurrencyCode;

/** Request body to authorise (reserve) funds against an account. */
public record AuthorisationRequest(
    @NotNull UUID accountId,
    @NotBlank @Size(max = 20) String idempotencyKey,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank
        @Size(min = 3, max = 3, message = "currencyCode must be exactly 3 characters")
        @ValidCurrencyCode
        String currencyCode,
    String merchantReference) {}
