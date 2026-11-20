package org.example.shared.correlation;

/**
 * Holds the {@link ScopedValue} used to propagate the current request's correlation ID onto worker
 * threads (e.g. the fraud-service HTTP call running on a virtual thread), as an alternative to
 * manually capturing/restoring {@link org.slf4j.MDC} state.
 *
 * <p>Unlike MDC, a {@link ScopedValue} binding is immutable and automatically unbound once the
 * bound block (see {@link ScopedValue#where}) returns, so there is no "previous value" to remember
 * or restore by convention.
 */
public final class CorrelationIdScope {
  public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

  private CorrelationIdScope() {}
}
