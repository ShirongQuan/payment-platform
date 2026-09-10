package org.example.fraud.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fraud.application.FraudService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry point for synchronous fraud evaluation, called by auth-service's fraud gateway. */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/fraud")
public class FraudController {

  private final FraudService fraudService;

  @PostMapping("/check")
  public ResponseEntity<FraudCheckResult> check(@Valid @RequestBody FraudCheckRequest request) {
    log.debug(
        "Received fraud check request, accountId={}, idempotencyKey={}",
        request.accountId(),
        request.idempotencyKey());
    // Outcome/latency metrics (including distinguishing a DUPLICATE replay from a fresh
    // APPROVE/DECLINE) are recorded in FraudServiceImpl, which is the only place with full
    // visibility of every mutually exclusive outcome - see FraudServiceImpl.check(...).
    FraudCheckResult result = fraudService.check(request);
    log.debug(
        "Returning fraud check result, accountId={}, idempotencyKey={}, resultType={}",
        request.accountId(),
        request.idempotencyKey(),
        result.getClass().getSimpleName());
    return ResponseEntity.ok(result);
  }
}
