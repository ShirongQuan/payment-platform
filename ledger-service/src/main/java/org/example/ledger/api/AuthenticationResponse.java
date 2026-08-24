package org.example.ledger.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response body for the authorisation query endpoint.
 *
 * <p>Despite the name (retained for API compatibility), this represents an authorisation's
 * ledger entry, not an authentication/security response.
 */
public record AuthenticationResponse(
    UUID authorisationId,
    UUID accountId,
    String merchantReference,
    BigDecimal amount,
    String currencyCode,
    String status,
    OffsetDateTime createdAt,
    UUID sourceEventId) {}
