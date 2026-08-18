package org.example.auth.fraud;

import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpFraudClient implements FraudClient {

  private final ResilientFraudGateway resilientFraudGateway;

  @Override
  public FraudDecision check(FraudCheckRequest request) {
    // synchronous facade for the service layer
    try {
      return resilientFraudGateway.checkAsync(request).join();
    } catch (Exception ex) {
      Throwable root = rootCause(ex);
      log.warn(
          "Fraud check join failed, accountId={}, idempotencyKey={}, errorType={}, errorMessage={}",
          request.accountId(),
          request.idempotencyKey(),
          root.getClass().getSimpleName(),
          root.getMessage());
      return FraudDecision.unavailable("fraud_join_exception:" + root.getClass().getSimpleName());
    }
  }

  private static Throwable rootCause(Throwable throwable) {
    if (throwable instanceof CompletionException completionException
        && completionException.getCause() != null) {
      return completionException.getCause();
    }
    return throwable;
  }
}
