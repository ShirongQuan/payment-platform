package org.example.fraud.exception;

import java.util.UUID;

public class FraudEvaluationInProgressException extends RuntimeException implements CodedException {

  public FraudEvaluationInProgressException(UUID evaluationId, String idempotencyKey) {
    super(
        "Fraud evaluation is still in progress for evaluationId="
            + evaluationId
            + ", idempotencyKey="
            + idempotencyKey);
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.FRAUD_EVALUATION_IN_PROGRESS;
  }
}

