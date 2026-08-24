package org.example.auth.authorisation.domain;

/** Reason code recorded alongside an authorisation lifecycle event, explaining why it occurred. */
public enum AuthorisationEventReason {
  /** No specific reason; used for normal successful transitions (e.g. AUTHORISED, CAPTURED). */
  NONE,
  /** Declined because the account's available balance was less than the requested amount. */
  INSUFFICIENT_FUNDS,
  ACCOUNT_LOCKED,
  ACCOUNT_INACTIVE,
  CURRENCY_MISMATCH,
  INVALID_AMOUNT,
  INVALID_STATE,
  AUTHORISATION_NOT_FOUND,
  DUPLICATE_REQUEST,
  /** Declined by the fraud pre-check. */
  FRAUD_DECLINED,
  /** Reversal requested by the customer. */
  CUSTOMER_REQUEST,
  /** Reversal requested by the merchant. */
  MERCHANT_REQUEST,
  EXPIRED,
  TIMEOUT,
  SYSTEM_ERROR
}
