package org.example.auth.fraud;

import java.util.List;

/**
 * Outcome of a fraud check for an authorisation request.
 *
 * <p>Use the static factories to construct instances: {@link #approve}, {@link #decline}, {@link
 * #declineWithLock}, or {@link #unavailable} when the fraud service could not be reached (risk
 * score {@code -1}).
 */
public record FraudDecision(
    FraudOutcome outcome,
    int riskScore,
    List<String> reasons,
    boolean lockAccountRecommended,
    String lockReasonCode) {
  public static FraudDecision approve(int riskScore, List<String> reasons) {
    return new FraudDecision(FraudOutcome.APPROVED, riskScore, reasons, false, null);
  }

  public static FraudDecision decline(int riskScore, List<String> reasons) {
    return new FraudDecision(FraudOutcome.DECLINED, riskScore, reasons, false, null);
  }

  /** Decline that also carries a recommendation from fraud-service to lock the account. */
  public static FraudDecision declineWithLock(
      int riskScore, List<String> reasons, String lockReasonCode) {
    return new FraudDecision(FraudOutcome.DECLINED, riskScore, reasons, true, lockReasonCode);
  }

  public static FraudDecision unavailable(String reason) {
    return new FraudDecision(FraudOutcome.UNAVAILABLE, -1, List.of(reason), false, null);
  }

  public boolean isDeclined() {
    return outcome == FraudOutcome.DECLINED;
  }

  public boolean isUnavailable() {
    return outcome == FraudOutcome.UNAVAILABLE;
  }
}
