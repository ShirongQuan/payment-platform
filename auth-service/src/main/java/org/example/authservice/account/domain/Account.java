package org.example.authservice.account.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.example.authservice.common.exception.InsufficientFundException;
import org.example.authservice.common.exception.InvalidCurrencyException;

/**
 * Domain model representing a payment account.
 *
 * <p>Enforces all business invariants: currency must be a valid ISO 4217 code, balances must be
 * non-negative, and reserved funds cannot exceed the available balance.
 *
 * <p>Use {@link #Account(String)} to create a new account, and the all-args constructor only for
 * rehydrating an existing account from persistence.
 */
@Getter
public class Account {

  private final UUID id;
  private final String currencyCode;
  private AccountStatus status;
  private BigDecimal availableBalance;
  private BigDecimal reservedBalance;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  /**
   * Creates a brand-new account with zero balances and ACTIVE status.
   *
   * @param currencyCode ISO 4217 currency code (e.g. "USD"); normalised to uppercase automatically
   * @throws InvalidCurrencyException if the currency code is not a valid ISO 4217 code
   */
  public Account(String currencyCode) {
    this.currencyCode = normalizeAndValidateCurrency(currencyCode);
    id = UUID.randomUUID();
    status = AccountStatus.ACTIVE;
    availableBalance = BigDecimal.ZERO;
    reservedBalance = BigDecimal.ZERO;
    createdAt = OffsetDateTime.now();
    updatedAt = OffsetDateTime.now();
  }

  /**
   * Rehydration constructor used to restore an account from persistence.
   *
   * <p>Validates all domain invariants: non-null fields, valid currency, non-negative balances.
   */
  public Account(
      UUID id,
      String currencyCode,
      AccountStatus status,
      BigDecimal availableBalance,
      BigDecimal reservedBalance,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = Objects.requireNonNull(id, "id cannot be null");
    this.currencyCode = normalizeAndValidateCurrency(currencyCode);
    this.status = Objects.requireNonNull(status, "status cannot be null");
    this.availableBalance = requireNonNegative(availableBalance, "availableBalance");
    this.reservedBalance = requireNonNegative(reservedBalance, "reservedBalance");
    this.createdAt = Objects.requireNonNull(createdAt);
    this.updatedAt = Objects.requireNonNull(updatedAt);
  }

  /** Asserts that a BigDecimal field is non-null and not negative. */
  private BigDecimal requireNonNegative(BigDecimal amount, String field) {
    Objects.requireNonNull(amount, field + " cannot be null");
    if (amount.signum() < 0) {
      throw new IllegalArgumentException(field + " cannot be negative");
    }
    return amount;
  }

  /** Trims, uppercases, and validates the currency code against Java's ISO 4217 registry. */
  private String normalizeAndValidateCurrency(String currencyCode) {
    Objects.requireNonNull(currencyCode);
    String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
    try {
      Currency.getInstance(normalized);
      return normalized;
    } catch (IllegalArgumentException e) {
      throw new InvalidCurrencyException(currencyCode);
    }
  }

  /**
   * Adds the given amount to the available balance.
   *
   * @param amount must be positive and non-null
   * @throws IllegalArgumentException if amount is zero or negative
   */
  public void deposit(BigDecimal amount) {
    validateAmount(amount);
    this.availableBalance = this.availableBalance.add(amount);
  }

  /**
   * Moves the given amount from available balance to reserved balance (e.g. for a pending payment).
   *
   * @param amount must be positive and non-null
   * @throws IllegalArgumentException    if amount is zero or negative
   * @throws InsufficientFundException   if available balance is less than the requested amount
   */
  public void reserve(BigDecimal amount) {
    validateAmount(amount);
    if (availableBalance.compareTo(amount) < 0) {
      throw new InsufficientFundException(availableBalance, amount);
    }
    availableBalance = availableBalance.subtract(amount);
    reservedBalance = reservedBalance.add(amount);
  }

  /** Validates that an amount is non-null and strictly positive. */
  private void validateAmount(BigDecimal amount) {
    Objects.requireNonNull(amount);
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("Amount must be positive");
    }
  }
}
