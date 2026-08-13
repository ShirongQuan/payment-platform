package org.example.fraud.api;

import java.util.UUID;

public record FraudPendingResponse(UUID evaluationId, String idempotencyKey, String status)
    implements FraudCheckResult {}
