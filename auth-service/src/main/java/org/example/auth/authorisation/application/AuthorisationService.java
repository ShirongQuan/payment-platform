package org.example.auth.authorisation.application;

import java.util.UUID;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;

public interface AuthorisationService {
  AuthorisationResponse authorise(AuthorisationRequest request);

  AuthorisationResponse getAuthorisationById(UUID authorisationId);
}
