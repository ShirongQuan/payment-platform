package org.example.authservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.authservice.common.exception.CurrencyMismatchException;
import org.example.authservice.common.exception.ErrorCode;
import org.example.authservice.common.exception.InsufficientFundException;
import org.example.authservice.common.exception.InvalidCurrencyException;
import org.junit.jupiter.api.Test;

class AccountTest {

  @Test
  void shouldCreateAccountWithCorrectInput() {
    String currencyCode = "GBP";
    Account account = new Account(currencyCode);
    assertThat(account.getId()).isNotNull();
    assertThat(account.getCurrencyCode()).isEqualTo(currencyCode);
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getCreatedAt()).isNotNull();
    assertThat(account.getUpdatedAt()).isNotNull();
  }

  @Test
  void shouldCreateAccountWithCorrectInputForRehydrationConstructor() {
    UUID id = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
    OffsetDateTime updatedAt = OffsetDateTime.now();

    Account account =
        new Account(
            id,
            "usd",
            AccountStatus.ACTIVE,
            BigDecimal.valueOf(100.00),
            BigDecimal.valueOf(10.00),
            createdAt,
            updatedAt);

    assertThat(account.getId()).isEqualTo(id);
    assertThat(account.getCurrencyCode()).isEqualTo("USD");
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.getAvailableBalance()).isEqualByComparingTo("100.00");
    assertThat(account.getReservedBalance()).isEqualByComparingTo("10.00");
    assertThat(account.getCreatedAt()).isEqualTo(createdAt);
    assertThat(account.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void shouldNotCreateAccountWithInvalidInput() {
    OffsetDateTime now = OffsetDateTime.now();

    assertThatThrownBy(() -> new Account((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("currencyCode cannot be null");

    assertThatThrownBy(() -> new Account("abc"))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currencyCode abc");

    assertThatThrownBy(
            () ->
                new Account(
                    null,
                    "USD",
                    AccountStatus.ACTIVE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    now,
                    now))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("id cannot be null");

    assertThatThrownBy(
            () ->
                new Account(
                    UUID.randomUUID(),
                    "USD",
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    now,
                    now))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("status cannot be null");

    assertThatThrownBy(
            () ->
                new Account(
                    UUID.randomUUID(),
                    "USD",
                    AccountStatus.ACTIVE,
                    BigDecimal.valueOf(-1),
                    BigDecimal.ZERO,
                    now,
                    now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("availableBalance cannot be negative");

    assertThatThrownBy(
            () ->
                new Account(
                    UUID.randomUUID(),
                    "USD",
                    AccountStatus.ACTIVE,
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(-1),
                    now,
                    now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reservedBalance cannot be negative");

    assertThatThrownBy(
            () ->
                new Account(
                    UUID.randomUUID(),
                    "USD",
                    AccountStatus.ACTIVE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    now))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () ->
                new Account(
                    UUID.randomUUID(),
                    "USD",
                    AccountStatus.ACTIVE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    now,
                    null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldDepositAccountWithCorrectInput() {
    Account account = new Account("USD");
    assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);

    account.deposit(BigDecimal.TEN, "USD");
    assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.TEN);
  }

  @Test
  void shouldFailDepositAccountWithInvalidInput() {
    Account account = new Account("USD");

    assertThatThrownBy(() -> account.deposit(BigDecimal.ONE, "EUR"))
        .isInstanceOf(CurrencyMismatchException.class)
        .hasMessageContaining("Currency mismatch")
        .satisfies(ex -> assertThat(((CurrencyMismatchException) ex).getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));

    assertThatThrownBy(() -> account.deposit(BigDecimal.ONE, "abc"))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currencyCode abc")
        .satisfies(ex -> assertThat(((InvalidCurrencyException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CURRENCY));

    assertThatThrownBy(() -> account.deposit(null, "USD"))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO, "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Amount must be positive");

    assertThatThrownBy(() -> account.deposit(BigDecimal.valueOf(-1), "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Amount must be positive");
  }

  @Test
  void shouldReserveWithCorrectInputAndSufficientFunds() {
    Account account = new Account("USD");
    account.deposit(BigDecimal.TEN, "USD");

    assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);

    account.reserve(BigDecimal.TEN, "USD");
    assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.TEN);
  }

  @Test
  void shouldNotReserveWithIncorrectInput() {
    Account account = new Account("USD");
    account.deposit(BigDecimal.TEN, "USD");

    assertThatThrownBy(() -> account.reserve(BigDecimal.ONE, "EUR"))
        .isInstanceOf(CurrencyMismatchException.class)
        .satisfies(ex -> assertThat(((CurrencyMismatchException) ex).getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));

    assertThatThrownBy(() -> account.reserve(BigDecimal.ONE, "abc"))
        .isInstanceOf(InvalidCurrencyException.class)
        .satisfies(ex -> assertThat(((InvalidCurrencyException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CURRENCY));

    assertThatThrownBy(() -> account.reserve(null, "USD"))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> account.reserve(BigDecimal.ZERO, "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Amount must be positive");

    assertThatThrownBy(() -> account.reserve(BigDecimal.valueOf(-1), "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Amount must be positive");
  }

  @Test
  void shouldNotReserveWithInsufficientFunds() {
    Account account = new Account("USD");
    account.deposit(BigDecimal.TEN, "USD");

    assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);

    assertThatThrownBy(() -> account.reserve(BigDecimal.valueOf(10.1), "USD"))
        .isInstanceOf(InsufficientFundException.class)
        .hasMessageContaining("Insufficient funds")
        .satisfies(
            ex -> {
              InsufficientFundException e = (InsufficientFundException) ex;
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
            });
  }

  @Test
  void shouldFailIncorrectOrMismatchCurrency() {
    Account account = new Account("USD");

    assertThatThrownBy(() -> account.validateCurrency("EUR"))
        .isInstanceOf(CurrencyMismatchException.class)
        .hasMessageContaining("Currency mismatch")
        .satisfies(ex -> assertThat(((CurrencyMismatchException) ex).getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));

    assertThatThrownBy(() -> account.validateCurrency("abc"))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currencyCode abc")
        .satisfies(ex -> assertThat(((InvalidCurrencyException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CURRENCY));

    assertThatThrownBy(() -> account.validateCurrency(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("currencyCode cannot be null");
  }

  @Test
  void shouldAllowCorrectAndMatchedCurrency() {
    Account account = new Account("USD");
    account.validateCurrency("USD");
    account.validateCurrency("usd");
    account.validateCurrency(" USD ");
  }
}
