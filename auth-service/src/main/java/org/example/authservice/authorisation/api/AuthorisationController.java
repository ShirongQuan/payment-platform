package org.example.authservice.authorisation.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.example.authservice.authorisation.application.AuthorisationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/authorisations")
public class AuthorisationController {
  private final AuthorisationService authorisationService;

  public AuthorisationController(AuthorisationService authorisationService) {
    this.authorisationService = authorisationService;
  }

  @PostMapping
  public AuthorisationResponse authorise(@RequestBody @Valid AuthorisationRequest request) {
    return this.authorisationService.authorise(request);
  }

  @GetMapping("/{authorisationId}")
  public AuthorisationResponse getAuthorisationById(@PathVariable UUID authorisationId) {
    return authorisationService.getAuthorisationById(authorisationId);
  }
}
