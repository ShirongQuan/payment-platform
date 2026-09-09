package org.example.fraud.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.example.shared.currency.ValidCurrencyCode;

/**
 * Inbound payload for {@code POST /fraud/check}, submitted by auth-service's fraud gateway.
 *
 * <p>{@code idempotencyKey} together with {@code accountId} uniquely identifies a fraud
 * evaluation attempt; resubmitting the same key with different field values raises an
 * idempotency conflict (see {@link org.example.fraud.exception.IdempotencyConflictException}).
 */
public record FraudCheckRequest(
    @NotNull UUID accountId,
    @NotBlank String idempotencyKey,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currencyCode,
    @NotBlank @Size(max = 128) String merchantReference,
    @NotBlank String ipAddress) {}
