package org.example.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private static final String OTHER_MDC_KEY = "tenantId";

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void shouldRemoveOnlyCorrelationIdWhenNoPreviousCorrelationExists()
      throws ServletException, IOException {
    CorrelationIdFilter filter = new CorrelationIdFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenInChain = new AtomicReference<>();

    MDC.put(OTHER_MDC_KEY, "tenant-a");

    filter.doFilter(
        request,
        response,
        // Simulate downstream chain execution and capture correlationId visible in MDC at that point.
        (req, res) -> seenInChain.set(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)));

    assertThat(seenInChain.get()).isNotBlank();
    assertThat(response.getHeader(CorrelationIdConstants.CORRELATION_ID_HEADER)).isEqualTo(seenInChain.get());
    assertThat(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)).isNull();
    assertThat(MDC.get(OTHER_MDC_KEY)).isEqualTo("tenant-a");
  }

  @Test
  void shouldRestorePreviousCorrelationIdAfterFilter()
      throws ServletException, IOException {
    CorrelationIdFilter filter = new CorrelationIdFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String previousCorrelationId = "11111111-1111-1111-1111-111111111111";

    MDC.put(OTHER_MDC_KEY, "tenant-b");
    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, previousCorrelationId);

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(CorrelationIdConstants.CORRELATION_ID_HEADER)).isNotBlank();
    assertThat(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)).isEqualTo(previousCorrelationId);
    assertThat(MDC.get(OTHER_MDC_KEY)).isEqualTo("tenant-b");
  }
}


