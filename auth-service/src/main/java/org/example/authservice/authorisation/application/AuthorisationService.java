package org.example.authservice.authorisation.application;

import java.util.UUID;
import org.example.authservice.authorisation.api.AuthorisationRequest;
import org.example.authservice.authorisation.api.AuthorisationResponse;

public interface AuthorisationService {
  AuthorisationResponse authorise(AuthorisationRequest request);

  AuthorisationResponse getAuthorisationById(UUID authorisationId);
}
