package org.example.fraud.failure;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Profile({"dev", "test"}) // important: disable in prod
@RequestMapping("/internal/test")
public class FailureModeController {

  private final FailureModeService failureModeService;

  @PostMapping("/failure-mode")
  public ResponseEntity<FailureModeResponse> setFailureMode(
      @RequestBody @Valid FailureModeRequest request) {
    FailureMode mode = failureModeService.setMode(request.mode());
    return ResponseEntity.ok(new FailureModeResponse(mode));
  }

  @GetMapping("/failure-mode")
  public ResponseEntity<FailureModeResponse> getFailureMode() {
    return ResponseEntity.ok(new FailureModeResponse(failureModeService.currentMode()));
  }
}
