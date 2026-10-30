package org.example.shared.correlation;

/**
 * Shared constants used by the correlation ID filter, interceptor, and resolver.
 *
 * <p>Centralizing the header name and MDC key here ensures every component that reads or writes
 * the correlation ID (inbound filter, outbound interceptor, logging) stays in sync.
 *
 * <p>This class is stateless and not meant to be instantiated.
 */
public final class CorrelationIdConstants {

  /** HTTP header name used to propagate the correlation ID between services. */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** SLF4J {@link org.slf4j.MDC} key under which the correlation ID is stored for logging. */
  public static final String CORRELATION_ID_MDC_KEY = "correlationId";

  private CorrelationIdConstants() {}
}

