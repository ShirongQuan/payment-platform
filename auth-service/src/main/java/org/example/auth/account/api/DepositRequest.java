package org.example.auth.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.example.shared.currency.ValidCurrencyCode;

/** Request body to deposit funds into an account's available balance. */
public record DepositRequest(
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currencyCode) {}
