package org.example.auth.authorisation.domain;

/**
 * Lifecycle status of an authorisation.
 *
 * <p>Valid transitions: {@code AUTHORISED -> CAPTURED}, {@code AUTHORISED -> REVERSED}. {@code
 * DECLINED} is a terminal state reached instead of {@code AUTHORISED} (fraud decline or
 * insufficient funds).
 */
public enum AuthorisationStatus {
  /** Funds have been reserved; can be captured or reversed. */
  AUTHORISED,
  /** Reserved funds have been settled/captured; terminal state. */
  CAPTURED,
  /** Reserved funds have been released back to the account; terminal state. */
  REVERSED,
  /** Authorisation was rejected (fraud decline or insufficient funds); terminal state. */
  DECLINED
}
