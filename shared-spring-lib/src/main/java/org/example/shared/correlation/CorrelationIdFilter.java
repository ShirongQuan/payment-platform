package org.example.shared.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String previousCorrelationId = MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);
    String correlationId = request.getHeader(CorrelationIdConstants.CORRELATION_ID_HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    } else {
      try {
        correlationId = UUID.fromString(correlationId).toString();
      } catch (IllegalArgumentException ex) {
        correlationId = UUID.randomUUID().toString();
      }
    }

    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, correlationId);
    response.setHeader(CorrelationIdConstants.CORRELATION_ID_HEADER, correlationId);
    log.debug(
        "Resolved inbound correlationId, method={}, uri={}, correlationId={}, previousCorrelationId={}",
        request.getMethod(),
        request.getRequestURI(),
        correlationId,
        previousCorrelationId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      if (previousCorrelationId == null) {
        MDC.remove(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);
      } else {
        MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, previousCorrelationId);
      }
      log.debug(
          "Restored correlationId in MDC after request completion, restoredCorrelationId={}",
          previousCorrelationId);
    }
  }
}
