package org.example.auth.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom constraint annotation that validates a String field or parameter as an ISO 4217 currency
 * code.
 *
 * <p>Usage: annotate any {@code String} field or parameter with {@code @ValidCurrencyCode}.
 * Validation is delegated to {@link CurrencyCodeValidator}.
 *
 * <pre>{@code
 * public record CreateAccountRequest(@ValidCurrencyCode String currencyCode) {}
 * }</pre>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = CurrencyCodeValidator.class)
public @interface ValidCurrencyCode {
  String message() default "Invalid currency code";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
