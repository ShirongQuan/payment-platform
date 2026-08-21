package org.example.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdResolverTest {

  private final CorrelationIdResolver resolver = new CorrelationIdResolver();

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void shouldReuseValidCorrelationIdFromMdc() {
    UUID existing = UUID.randomUUID();
    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, existing.toString());

    UUID resolved = resolver.resolveOrCreate();

    assertThat(resolved).isEqualTo(existing);
    assertThat(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)).isEqualTo(existing.toString());
  }

  @Test
  void shouldGenerateAndWriteBackWhenMdcIsMissing() {
    UUID resolved = resolver.resolveOrCreate();

    assertThat(resolved).isNotNull();
    assertThat(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)).isEqualTo(resolved.toString());
  }

  @Test
  void shouldGenerateAndWriteBackWhenMdcValueIsInvalid() {
    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "not-a-uuid");

    UUID resolved = resolver.resolveOrCreate();

    assertThat(resolved).isNotNull();
    assertThat(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)).isEqualTo(resolved.toString());
  }
}


