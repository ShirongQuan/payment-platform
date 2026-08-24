package org.example.fraud.domain;

/** Lifecycle/outcome states for a fraud evaluation. */
public enum FraudDecision {
  /** Evaluation row has been claimed but rule scoring has not finalized yet. */
  PENDING,
  /** Total risk score was below the decline threshold. */
  APPROVE,
  /** Total risk score met or exceeded the decline threshold. */
  DECLINE
}
