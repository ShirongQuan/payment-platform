package org.example.shared.correlation;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdInterceptor.class);

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
