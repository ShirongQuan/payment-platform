package org.example.auth.fraud;

/** Possible outcomes of a fraud check. */
public enum FraudOutcome {
  /** The request is approved to proceed. */
  APPROVE,
  /** The request is rejected due to fraud risk. */
  DECLINE,
  /** The fraud service could not be reached or timed out; no decision was made. */
  UNAVAILABLE
}
