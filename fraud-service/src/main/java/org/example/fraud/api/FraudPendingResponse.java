package org.example.fraud.api;

import java.util.UUID;

/**
 * Response shape indicating a fraud evaluation for this idempotency key is still in progress
 * (currently unused directly by the controller, which instead surfaces this state as a 409 via
 * {@link org.example.fraud.exception.FraudEvaluationInProgressException}).
 */
public record FraudPendingResponse(UUID evaluationId, String idempotencyKey, String status)
    implements FraudCheckResult {}
