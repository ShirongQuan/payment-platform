package org.example.shared.currency;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;
import java.util.Locale;

/**
 * Bean Validation implementation for {@link org.example.shared.currency.ValidCurrencyCode}.
 *
 * <p>Accepts any non-blank string that resolves to a valid ISO 4217 currency via {@link
 * java.util.Currency#getInstance(String)}. The value is trimmed and uppercased before checking, so
 * "usd" and " USD " are both accepted.
 */
public class CurrencyCodeValidator implements ConstraintValidator<ValidCurrencyCode, String> {
  /**
   * Checks whether {@code value} is a non-blank string that resolves to a valid ISO 4217 currency
   * code, ignoring leading/trailing whitespace and case.
   *
   * @param value the candidate currency code
   * @param constraintValidatorContext unused; required by the {@link ConstraintValidator} contract
   * @return {@code true} if {@code value} is a recognized ISO 4217 currency code, {@code false}
   *     otherwise (including {@code null}/blank input)
   */
  @Override
  public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      Currency.getInstance(value.trim().toUpperCase(Locale.ROOT));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
