package org.example.ledger.domain;

/**
 * Authorisation lifecycle event types consumed from the {@code auth.events} Kafka topic.
 *
 * <p>Each value (except {@link #AUTHORISATION_DECLINED}) is applied to the ledger projections by
 * a dedicated command handler; declined events carry no monetary effect and are ignored by the
 * ledger.
 */
public enum EventType {
  /** An authorisation was approved and funds were reserved. */
  AUTHORISATION_AUTHORISED,
  /** An authorisation was declined; no ledger entry is created. */
  AUTHORISATION_DECLINED,
  /** A previously authorised amount was captured (settled). */
  AUTHORISATION_CAPTURED,
  /** A previously authorised amount was reversed (released). */
  AUTHORISATION_REVERSED
}
