package org.example.auth.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.example.auth.common.validation.ValidCurrencyCode;

// ^ = start of the string
// {3} = exactly 3 of the previous token
// $ = end of the string

public record CreateAccountRequest(
    @NotBlank
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currencyCode must contain exactly 3 letters")
        @ValidCurrencyCode
        String currencyCode) {}
