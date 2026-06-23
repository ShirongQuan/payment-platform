package org.example.authservice.account.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.authservice.account.domain.AccountStatus;

public record AccountResponse(
    UUID accountId,
    AccountStatus status,
    String currencyCode,
    BigDecimal availableBalance,
    BigDecimal reservedBalance,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
