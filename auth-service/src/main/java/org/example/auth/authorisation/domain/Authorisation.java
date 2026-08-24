package org.example.auth.authorisation.domain;

import static org.example.auth.common.validation.ValidationHelpers.normalizeAndValidateCurrency;
import static org.example.auth.common.validation.ValidationHelpers.requireNonNegativeAmount;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * Domain model representing a single authorisation (a reservation of funds against an account).
 *
 * <p>An authorisation transitions through {@link AuthorisationStatus} states over its lifecycle:
 * created as {@code AUTHORISED} or {@code DECLINED}, then optionally {@code CAPTURED} or {@code
 * REVERSED}. Each transition is recorded as a separate {@code AuthorisationEvent} row (see {@link
 * org.example.auth.authorisation.infrastructure.AuthorisationEventEntity}) for audit/idempotency.
 *
 * <p>Use the 5-arg constructor to create a brand-new authorisation, and the 8-arg constructor only
 * for rehydrating an existing authorisation from persistence.
 */
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

  /** Creates a brand-new authorisation with a freshly generated id and current timestamps. */
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

  /** Rehydration constructor used to restore an authorisation from persistence. */
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
