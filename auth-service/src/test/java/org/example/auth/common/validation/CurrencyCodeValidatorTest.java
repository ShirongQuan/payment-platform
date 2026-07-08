package org.example.auth.common.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CurrencyCodeValidatorTest {
  private final CurrencyCodeValidator validator = new CurrencyCodeValidator();

  @Test
  void shouldReturnFalseWhenValueIsNull() {
    assertFalse(validator.isValid(null, null));
  }

  @Test
  void shouldReturnFalseWhenValueIsBlank() {
    assertFalse(validator.isValid("", null));
    assertFalse(validator.isValid("   ", null));
  }

  @Test
  void shouldReturnTrueForValidCurrencyCode() {
    assertTrue(validator.isValid("USD", null));
    assertTrue(validator.isValid("EUR", null));
  }

  @Test
  void shouldAcceptLowercaseAndTrimmedCurrencyCode() {
    assertTrue(validator.isValid("usd", null));
    assertTrue(validator.isValid(" gbp ", null));
    assertTrue(validator.isValid("jPy", null));
  }

  @Test
  void shouldReturnFalseForInvalidCurrencyCode() {
    assertFalse(validator.isValid("ABC", null));
    assertFalse(validator.isValid("US", null));
    assertFalse(validator.isValid("USDD", null));
  }
}
