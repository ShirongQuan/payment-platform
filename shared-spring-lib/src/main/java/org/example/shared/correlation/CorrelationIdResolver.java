package org.example.shared.correlation;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public final class CorrelationIdResolver {
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
