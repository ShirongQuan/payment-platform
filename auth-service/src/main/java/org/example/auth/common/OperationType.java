package org.example.auth.common;

/** Identifies which authorisation lifecycle operation is being performed; used to scope idempotency keys. */
public enum OperationType {
  AUTHORISE,
  CAPTURE,
  REVERSE
}
