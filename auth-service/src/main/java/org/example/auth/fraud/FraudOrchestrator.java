package org.example.auth.fraud;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudOrchestrator {

  private static final int RISK_SCORE_WHEN_FRAUD_SERVICE_UNAVAILABLE = 100;
  private static final int RISK_SCORE_WHEN_FAIL_OPEN = 40;

  private static final String FRAUD_UNAVAILABLE_REASON = "FRAUD_SERVICE_UNAVAILABLE";
  private static final String FAIL_OPEN_REASON = "FRAUD_UNAVAILABLE_TRUSTED_TINY_AMOUNT";

  private final FraudClient fraudClient;
  private final FailOpenPolicy failOpenPolicy;

  public FraudDecision evaluate(
      AuthorisationRequest request,
      String normalizedCurrency,
      String ipAddress,
      UUID correlationId) {
    FraudCheckRequest fraudReq =
        new FraudCheckRequest(
            request.accountId(),
            request.idempotencyKey(),
            request.amount(),
            normalizedCurrency,
            request.merchantReference(),
            ipAddress,
            correlationId);

    FraudDecision raw = fraudClient.check(fraudReq);

    // fallback policy when fraud unavailable
    if (raw.isUnavailable()) {
      List<String> unavailableReasons = unavailableReasons(raw.reasons());
      if (failOpenPolicy.allow(request.accountId(), request.amount())) {
        log.warn(
            "Applying fail-open policy for trusted tiny-amount transaction, accountId={}, idempotencyKey={}, amount={}, currencyCode={}, fraudReasons={}",
            request.accountId(),
            request.idempotencyKey(),
            request.amount(),
            normalizedCurrency,
            unavailableReasons);
        List<String> reasons = new ArrayList<>(unavailableReasons);
        reasons.add(FAIL_OPEN_REASON);
        return FraudDecision.approve(RISK_SCORE_WHEN_FAIL_OPEN, reasons);
      }

      log.warn(
          "Declining authorisation because fraud service is unavailable, accountId={}, idempotencyKey={}, amount={}, currencyCode={}, fraudReasons={}",
          request.accountId(),
          request.idempotencyKey(),
          request.amount(),
          normalizedCurrency,
          unavailableReasons);
      return FraudDecision.decline(RISK_SCORE_WHEN_FRAUD_SERVICE_UNAVAILABLE, unavailableReasons);
    }

    return raw;
  }

  public boolean shouldDecline(FraudDecision d) {
    return d.isDeclined();
  }

  private static List<String> unavailableReasons(List<String> rawReasons) {
    List<String> reasons = new ArrayList<>();
    reasons.add(FRAUD_UNAVAILABLE_REASON);
    if (rawReasons != null) {
      reasons.addAll(rawReasons);
    }
    return reasons;
  }
}
