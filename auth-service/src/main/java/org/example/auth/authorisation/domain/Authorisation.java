package org.example.auth.authorisation.domain;

import static org.example.auth.common.validation.ValidationHelpers.normalizeAndValidateCurrency;
import static org.example.auth.common.validation.ValidationHelpers.requireNonNegativeAmount;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Getter
public class Authorisation {
  private final UUID id;
  private final UUID accountId;
  private final BigDecimal amount;
  private final String currencyCode;
  private final String merchantReference;
  private AuthorisationStatus status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Authorisation(
      UUID accountId,
      BigDecimal amount,
      String currencyCode,
      String merchantReference,
      AuthorisationStatus status) {
    id = UUID.randomUUID();
    this.accountId = Objects.requireNonNull(accountId, "accountId cannot be null");
    this.merchantReference = merchantReference;
    this.amount = requireNonNegativeAmount(amount, "amount");
    this.currencyCode = normalizeAndValidateCurrency(currencyCode);
    this.status = Objects.requireNonNull(status);
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = OffsetDateTime.now();
  }

  public Authorisation(
      UUID id,
      UUID accountId,
      BigDecimal amount,
      String currencyCode,
      String merchantReference,
      AuthorisationStatus status,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = Objects.requireNonNull(id);
    this.accountId = Objects.requireNonNull(accountId, "accountId cannot be null");
    this.merchantReference = merchantReference;
    this.amount = requireNonNegativeAmount(amount, "amount");
    this.currencyCode = normalizeAndValidateCurrency(currencyCode);
    this.status = Objects.requireNonNull(status);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.updatedAt = Objects.requireNonNull(updatedAt);
  }
}
