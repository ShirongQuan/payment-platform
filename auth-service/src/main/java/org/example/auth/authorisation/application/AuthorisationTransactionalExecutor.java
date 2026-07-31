package org.example.auth.authorisation.application;

import java.util.UUID;

import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;

public interface AuthorisationTransactionalExecutor {
  CaptureResponse captureInTransaction(UUID authorisationId, CaptureRequest captureRequest);

  AuthorisationResponse authoriseInTransaction(
      AuthorisationRequest request, String normalizedCurrency);
}
