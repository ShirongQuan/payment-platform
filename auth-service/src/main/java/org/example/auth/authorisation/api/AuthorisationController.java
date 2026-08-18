package org.example.auth.authorisation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.authorisation.application.AuthorisationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for authorisation, capture, and reversal operations. */
@Slf4j
@RestController
@RequestMapping("/authorisations")
public class AuthorisationController {
  private final AuthorisationService authorisationService;

  public AuthorisationController(AuthorisationService authorisationService) {
    this.authorisationService = authorisationService;
  }

  @PostMapping
  public AuthorisationResponse authorise(
      @RequestBody @Valid AuthorisationRequest request, HttpServletRequest servletRequest) {
    String clientIpAddress = extractClientIpAddress(servletRequest);
    log.debug(
        "Received authorise request, accountId={}, idempotencyKey={}, clientIpAddress={}",
        request.accountId(),
        request.idempotencyKey(),
        clientIpAddress);
    return this.authorisationService.authorise(request, clientIpAddress);
  }

  private String extractClientIpAddress(HttpServletRequest request) {
    // Forwarded headers are handled by Spring/server strategy when enabled.
    String remoteAddr = request.getRemoteAddr();
    return (remoteAddr == null || remoteAddr.isBlank()) ? "0.0.0.0" : remoteAddr;
  }

  @GetMapping("/{authorisationId}")
  public AuthorisationResponse getAuthorisationById(@PathVariable UUID authorisationId) {
    log.debug("Received get authorisation request, authorisationId={}", authorisationId);
    return authorisationService.getAuthorisationById(authorisationId);
  }

  @PostMapping("/{authorisationId}/captures")
  public CaptureResponse capture(
      @PathVariable UUID authorisationId,
      @RequestBody @NotNull @Valid CaptureRequest captureRequest) {
    log.debug(
        "Received capture request, authorisationId={}, idempotencyKey={}",
        authorisationId,
        captureRequest.idempotencyKey());
    return authorisationService.capture(authorisationId, captureRequest);
  }

  @PostMapping("/{authorisationId}/reversals")
  public ReverseResponse reverse(
      @PathVariable UUID authorisationId,
      @RequestBody @NotNull @Valid ReverseRequest reverseRequest) {
    log.debug(
        "Received reverse request, authorisationId={}, idempotencyKey={}",
        authorisationId,
        reverseRequest.idempotencyKey());
    return authorisationService.reverse(authorisationId, reverseRequest);
  }
}
