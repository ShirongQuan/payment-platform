package org.example.fraud.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.fraud.application.FraudService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/fraud")
public class FraudController {

  private final FraudService fraudService;

  @PostMapping("/check")
  public ResponseEntity<FraudCheckResult> check(@Valid @RequestBody FraudCheckRequest request) {
    FraudCheckResult result = fraudService.check(request);
    return ResponseEntity.ok(result);
  }
}
