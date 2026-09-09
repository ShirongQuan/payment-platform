package org.example.auth.fraud;

/** Possible outcomes of a fraud check. */
public enum FraudOutcome {
  /** The request is approved to proceed. */
  APPROVED,
  /** The request is rejected due to fraud risk. */
  DECLINED,
  /** The fraud service could not be reached or timed out; no decision was made. */
  UNAVAILABLE
}
