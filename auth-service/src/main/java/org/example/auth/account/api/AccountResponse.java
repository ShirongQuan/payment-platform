package org.example.auth.account.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.account.domain.AccountStatus;

/** Response body describing the current state of an account. */
public record AccountResponse(
    UUID accountId,
    AccountStatus status,
    String currencyCode,
    BigDecimal availableBalance,
    BigDecimal reservedBalance,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
