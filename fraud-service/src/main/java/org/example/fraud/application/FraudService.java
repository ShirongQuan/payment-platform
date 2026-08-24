package org.example.fraud.application;

import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.api.FraudCheckResult;

/** Public contract for synchronous fraud evaluation, implemented by {@link FraudServiceImpl}. */
public interface FraudService {

  /**
   * Evaluates a fraud check request and returns the decision (or a replayed decision if this is a
   * duplicate of a previously processed request identified by accountId + idempotencyKey).
   */
  FraudCheckResult check(FraudCheckRequest request);
}
