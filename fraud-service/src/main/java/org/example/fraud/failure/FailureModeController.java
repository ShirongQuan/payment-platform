package org.example.fraud.failure;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Test-only endpoints to remotely toggle chaos/failure injection on this instance (see
 * {@link FailureModeService}). Restricted to the {@code dev}/{@code test} profiles so it can never
 * be reached in production.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Profile({"dev", "test"}) // important: disable in prod
@RequestMapping("/internal/test")
public class FailureModeController {

  private final FailureModeService failureModeService;

  /** Sets the active failure mode for subsequent fraud check requests. */
  @PostMapping("/failure-mode")
  public ResponseEntity<FailureModeResponse> setFailureMode(
      @RequestBody @Valid FailureModeRequest request) {
    log.debug("Received request to set fraud failure mode to {}", request.mode());
    FailureMode mode = failureModeService.setMode(request.mode());
    return ResponseEntity.ok(new FailureModeResponse(mode));
  }

  /** Returns the currently active failure mode. */
  @GetMapping("/failure-mode")
  public ResponseEntity<FailureModeResponse> getFailureMode() {
    return ResponseEntity.ok(new FailureModeResponse(failureModeService.currentMode()));
  }
}
