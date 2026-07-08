package org.example.auth.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.example.auth.common.validation.ValidCurrencyCode;

// ^ = start of the string
// \s = any white space character
// {3} = exactly 3 of the previous token
// $ = end of the string

public record CreateAccountRequest(
    @NotBlank
        @Pattern(
            regexp = "^\\s*[A-Za-z]{3}\\s*$",
            message = "currencyCode must contain exactly 3 letters (surrounding spaces allowed)")
        @ValidCurrencyCode
        String currencyCode) {}
