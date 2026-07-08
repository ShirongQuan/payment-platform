package org.example.auth.authorisation.domain;

import static org.example.auth.common.validation.ValidationHelpers.normalizeAndValidateCurrency;
import static org.example.auth.common.validation.ValidationHelpers.requireNonNegativeAmount;
import static org.example.auth.common.validation.ValidationHelpers.validateStringField;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Getter
public class Authorisation {
  private final UUID id;
  private final UUID accountId;
  private final String idempotencyKey;
  private final BigDecimal amount;
  private final String currencyCode;
  private final String merchantReference;
  private AuthorisationStatus status;
  private String failureReason;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Authorisation(
      UUID accountId,
      String idempotencyKey,
      BigDecimal amount,
      String currencyCode,
      String merchantReference,
      AuthorisationStatus status,
      String failureReason) {
    id = UUID.randomUUID();
    this.accountId = Objects.requireNonNull(accountId, "accountId cannot be null");
    this.idempotencyKey = validateStringField(idempotencyKey, "idempotencyKey");
    this.merchantReference = merchantReference;
    this.amount = requireNonNegativeAmount(amount, "amount");
    this.currencyCode = normalizeAndValidateCurrency(currencyCode);
    this.status = Objects.requireNonNull(status);
    this.failureReason = Objects.requireNonNull(failureReason);
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = OffsetDateTime.now();
  }

  public Authorisation(
      UUID id,
      UUID accountId,
      String idempotencyKey,
      BigDecimal amount,
      String currencyCode,
      String merchantReference,
      AuthorisationStatus status,
      String failureReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = Objects.requireNonNull(id);
    this.accountId = Objects.requireNonNull(accountId, "accountId cannot be null");
    this.idempotencyKey = validateStringField(idempotencyKey, "idempotencyKey");
    this.merchantReference = merchantReference;
    this.amount = requireNonNegativeAmount(amount, "amount");
    this.currencyCode = normalizeAndValidateCurrency(currencyCode);
    this.status = Objects.requireNonNull(status);
    this.failureReason = Objects.requireNonNull(failureReason);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.updatedAt = Objects.requireNonNull(updatedAt);
  }
}
