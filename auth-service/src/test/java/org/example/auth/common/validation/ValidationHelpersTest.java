package org.example.auth.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.auth.common.validation.ValidationHelpers.normalizeAndValidateCurrency;
import static org.example.auth.common.validation.ValidationHelpers.requireNonNegativeAmount;

import java.math.BigDecimal;
import org.example.auth.common.exception.ErrorCode;
import org.example.auth.common.exception.InvalidCurrencyException;
import org.junit.jupiter.api.Test;

class ValidationHelpersTest {

  @Test
  void shouldRejectNullOrNegativeAmountForField() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> requireNonNegativeAmount(BigDecimal.valueOf(-1.0), "fieldName"))
        .withMessage("fieldName cannot be negative");

    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> requireNonNegativeAmount(null, "fieldName"))
        .withMessage("fieldName cannot be null");
  }

  @Test
  void shouldAcceptNonNegativeAmountForField() {
    assertThat(BigDecimal.ZERO)
        .isEqualByComparingTo(requireNonNegativeAmount(BigDecimal.ZERO, "fieldName"));

    assertThat(BigDecimal.valueOf(1.0))
        .isEqualByComparingTo(requireNonNegativeAmount(BigDecimal.valueOf(1.000), "fieldName"));
  }

  @Test
  void shouldRejectInvalidCurrency() {
    // reject null value
    assertThatThrownBy(() -> normalizeAndValidateCurrency(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("cannot be null");

    // reject wrong currency code
    assertThatThrownBy(() -> normalizeAndValidateCurrency("abc"))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currencyCode abc")
        .satisfies(
            ex -> {
              InvalidCurrencyException e = (InvalidCurrencyException) ex;
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURRENCY);
            });

    // reject blank value
    assertThatThrownBy(() -> normalizeAndValidateCurrency("  "))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currencyCode")
        .satisfies(
            ex -> {
              InvalidCurrencyException e = (InvalidCurrencyException) ex;
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURRENCY);
            });
  }

  @Test
  void shouldAcceptValidCurrency() {
    assertThat(normalizeAndValidateCurrency("gbp ")).isEqualTo("GBP");
    assertThat(normalizeAndValidateCurrency(" USD ")).isEqualTo("USD");
  }

  @Test
  void shouldRejectNullOrNonPositiveAmount() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> ValidationHelpers.validateAmount(null))
        .withMessage(null);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ValidationHelpers.validateAmount(BigDecimal.ZERO))
        .withMessage("Amount must be positive");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ValidationHelpers.validateAmount(BigDecimal.valueOf(-1)))
        .withMessage("Amount must be positive");
  }

  @Test
  void shouldAcceptPositiveAmount() {
    ValidationHelpers.validateAmount(BigDecimal.valueOf(0.01));
  }

  @Test
  void shouldRejectNullOrBlankStringField() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> ValidationHelpers.validateStringField(null, "fieldName"))
        .withMessage("fieldName cannot be null");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ValidationHelpers.validateStringField(" ", "fieldName"))
        .withMessage("fieldName cannot be blank");
  }

  @Test
  void shouldAcceptNonBlankStringField() {
    assertThat(ValidationHelpers.validateStringField("hello", "fieldName")).isEqualTo("hello");
  }
}
