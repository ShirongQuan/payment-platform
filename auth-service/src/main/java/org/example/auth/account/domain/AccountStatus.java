package org.example.auth.account.domain;

/** Lifecycle status of a payment account. */
public enum AccountStatus {
  /** Normal operating status; deposits, reservations, captures and reversals are permitted. */
  ACTIVE,
  /**
   * Account is temporarily locked (e.g. suspected fraud); mutating operations should be rejected.
   */
  LOCKED
}
