package org.example.authservice.account.api;

import jakarta.validation.constraints.NotBlank;
import org.example.authservice.account.validation.ValidCurrencyCode;

public record CreateAccountRequest(@NotBlank @ValidCurrencyCode String currencyCode) {}
