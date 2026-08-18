package org.example.auth.fraud;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResilientFraudGateway {

  private final RestClient fraudRestClient;

  @TimeLimiter(name = "fraudService")
  @CircuitBreaker(name = "fraudService", fallbackMethod = "fallback")
  public CompletableFuture<FraudDecision> checkAsync(FraudCheckRequest request) {
    return CompletableFuture.supplyAsync(
        () -> {
          FraudCheckResponse resp =
              fraudRestClient
                  .post()
                  .uri("/fraud/check")
                  .body(request)
                  .retrieve()
                  .body(FraudCheckResponse.class);

          if (resp == null) return FraudDecision.unavailable("fraud_null_response");

          if ("DECLINE".equalsIgnoreCase(resp.decision())) {
            return FraudDecision.decline(
                resp.riskScore(),
                resp.reasons() == null
                    ? List.of()
                    : resp.reasons().stream().map(RuleResult::toString).toList());
          }
          return FraudDecision.approve(
              resp.riskScore(), resp.reasons().stream().map(Record::toString).toList());
        });
  }

  @SuppressWarnings("unused")
  private CompletableFuture<FraudDecision> fallback(FraudCheckRequest req, Throwable t) {
    String reason = unavailableReasonFromThrowable(t);
    log.warn(
        "Fraud gateway fallback triggered, accountId={}, idempotencyKey={}, reason={}, errorType={}, errorMessage={}",
        req.accountId(),
        req.idempotencyKey(),
        reason,
        t.getClass().getSimpleName(),
        t.getMessage());
    return CompletableFuture.completedFuture(FraudDecision.unavailable(reason));
  }

  private static String unavailableReasonFromThrowable(Throwable throwable) {
    if (hasCause(throwable, TimeoutException.class)) {
      return "fraud_fallback:TIMEOUT";
    }
    if (hasCause(throwable, CallNotPermittedException.class)) {
      return "fraud_fallback:CIRCUIT_OPEN";
    }
    if (hasCause(throwable, HttpMessageConversionException.class)) {
      return "fraud_fallback:DESERIALIZATION";
    }
    return "fraud_fallback:" + throwable.getClass().getSimpleName();
  }

  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> targetType) {
    Throwable current = throwable;
    while (current != null) {
      if (targetType.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
