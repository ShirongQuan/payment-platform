package org.example.auth.fraud;

/** Synchronous facade for performing a fraud check; never throws for downstream unavailability. */
public interface FraudClient {

  /**
   * Evaluates a fraud check request, returning {@link FraudDecision#isUnavailable()} (rather than
   * throwing) if the fraud service could not be reached or timed out.
   */
  FraudDecision check(FraudCheckRequest request);
}
