package org.example.authservice.authorisation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.authservice.common.exception.InvalidCurrencyException;
import org.junit.jupiter.api.Test;

class AuthorisationTest {
  @Test
  void shouldCreateAuthorisationWithCorrectInput() {
    UUID accountId = UUID.randomUUID();
    Authorisation authorisation =
        new Authorisation(
            accountId,
            "idempotencyKey",
            BigDecimal.TEN,
            "GBP",
            "reference",
            AuthorisationStatus.AUTHORISED,
            "");
    assertThat(authorisation.getAccountId()).isEqualTo(accountId);
    assertThat(authorisation.getIdempotencyKey()).isEqualTo("idempotencyKey");
    assertThat(authorisation.getAmount()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(authorisation.getCurrencyCode()).isEqualTo("GBP");
    assertThat(authorisation.getMerchantReference()).isEqualTo("reference");
    assertThat(authorisation.getStatus()).isEqualTo(AuthorisationStatus.AUTHORISED);
    assertThat(authorisation.getFailureReason()).isEqualTo("");
  }

  @Test
  void shouldNotCreateAuthorisationWithInvalidInput() {
    // blank idempotency key
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    " ",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("idempotencyKey cannot be blank");

    // null idempotency key
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    null,
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("idempotencyKey cannot be null");

    // null account id
    assertThatThrownBy(
            () ->
                new Authorisation(
                    null,
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("accountId cannot be null");

    // null amount
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    "idempotencyKey",
                    null,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("amount cannot be null");

    // negative amount
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.valueOf(-1),
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amount cannot be negative");

    // invalid currency code
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "XXX_INVALID",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(InvalidCurrencyException.class);

    // null currency code
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    null,
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    ""))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("currencyCode cannot be null");

    // null status
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    null,
                    ""))
        .isInstanceOf(NullPointerException.class);

    // null failure reason
    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldNotCreateAuthorisationFromPersistenceWithInvalidInput() {
    OffsetDateTime now = OffsetDateTime.now();

    assertThatThrownBy(
            () ->
                new Authorisation(
                    null,
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    "",
                    now,
                    now))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    "",
                    null,
                    now))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () ->
                new Authorisation(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "idempotencyKey",
                    BigDecimal.TEN,
                    "GBP",
                    "reference",
                    AuthorisationStatus.AUTHORISED,
                    "",
                    now,
                    null))
        .isInstanceOf(NullPointerException.class);
  }
}
