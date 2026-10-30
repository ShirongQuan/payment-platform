package org.example.shared.correlation;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * {@link ClientHttpRequestInterceptor} that propagates the current correlation ID onto outbound
 * {@code RestTemplate} requests.
 *
 * <p>Reads the correlation ID previously stored in the SLF4J {@link MDC} (typically by {@link
 * CorrelationIdFilter} for the inbound request) and attaches it as the {@code
 * X-Correlation-Id} header on downstream calls, so a single logical request can be traced across
 * service boundaries. If no correlation ID is present in the MDC, the request is forwarded
 * unchanged.
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdInterceptor.class);

  /**
   * Attaches the MDC-resolved correlation ID (if any) to the outbound request header before
   * delegating to the rest of the interceptor chain.
   *
   * @param request the outbound HTTP request
   * @param body the outbound request body
   * @param execution the next step in the interceptor chain
   * @return the response produced by {@code execution}
   */
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String correlationId = MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);
    if (correlationId != null) {
      request.getHeaders().set(CorrelationIdConstants.CORRELATION_ID_HEADER, correlationId);
      log.debug(
          "Attached correlationId to outbound HTTP request, method={}, uri={}, correlationId={}",
          request.getMethod(),
          request.getURI(),
          correlationId);
    } else {
      log.debug(
          "No correlationId in MDC for outbound HTTP request, method={}, uri={}",
          request.getMethod(),
          request.getURI());
    }
    return execution.execute(request, body);
  }
}
