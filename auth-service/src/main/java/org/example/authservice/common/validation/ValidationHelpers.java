package org.example.authservice.common.validation;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import org.example.authservice.common.exception.InvalidCurrencyException;

public final class ValidationHelpers {

  private ValidationHelpers() {}

  /** Asserts that a BigDecimal field is non-null and not negative. */
  public static BigDecimal requireNonNegativeAmount(BigDecimal amount, String field) {
    Objects.requireNonNull(amount, field + " cannot be null");
    if (amount.signum() < 0) {
      throw new IllegalArgumentException(field + " cannot be negative");
    }
    return amount;
  }

  /** Trims, uppercases, and validates the currency code against Java's ISO 4217 registry. */
  public static String normalizeAndValidateCurrency(String currencyCode) {
    Objects.requireNonNull(currencyCode, "currencyCode cannot be null");
    String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
    try {
      Currency.getInstance(normalized);
      return normalized;
    } catch (IllegalArgumentException e) {
      throw new InvalidCurrencyException(currencyCode);
    }
  }

  /** Validates that an amount is non-null and strictly positive. */
  public static void validateAmount(BigDecimal amount) {
    Objects.requireNonNull(amount);
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("Amount must be positive");
    }
  }

  public static String validateStringField(String value, String field) {
    Objects.requireNonNull(value, field + " cannot be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " cannot be blank");
    }
    return value;
  }
}
