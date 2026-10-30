package org.example.shared.correlation;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * Resolves the current correlation ID for call sites that are not part of the servlet request
 * lifecycle (e.g. Kafka consumers, scheduled jobs).
 *
 * <p>Unlike {@link CorrelationIdFilter}, which only runs for inbound HTTP requests, this resolver
 * can be injected anywhere a correlation ID is needed: it reads the current value from the SLF4J
 * {@link MDC}, validates it, and falls back to generating a new {@link UUID} if the MDC is empty
 * or holds an invalid value. The resolved value is written back into the MDC so subsequent log
 * statements and propagation stay consistent.
 */
@Slf4j
public final class CorrelationIdResolver {
  /**
   * Returns the correlation ID currently stored in the MDC, parsed as a {@link UUID}, generating
   * and storing a new one if the MDC is empty or contains an invalid value.
   *
   * @return the resolved (or newly generated) correlation ID as a {@link UUID}
   */
  public UUID resolveOrCreate() {
    String correlationIdValue = MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);
    if (correlationIdValue == null || correlationIdValue.isBlank()) {
      UUID generated = UUID.randomUUID();
      MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, generated.toString());
      return generated;
    }

    try {
      UUID parsed = UUID.fromString(correlationIdValue);
      // Normalize MDC value so downstream log/header propagation always uses canonical UUID text.
      MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, parsed.toString());
      return parsed;
    } catch (IllegalArgumentException ex) {
      UUID generated = UUID.randomUUID();
      log.warn("Invalid correlationId in MDC, generating a new UUID, value={}", correlationIdValue);
      MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, generated.toString());
      return generated;
    }
  }
}
