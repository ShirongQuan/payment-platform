package org.example.auth.authorisation.application;

import java.util.UUID;

import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;

/** Transaction-scoped executor for authorisation lifecycle state transitions. */
public interface AuthorisationTransactionalExecutor {
  CaptureResponse captureInTransaction(UUID authorisationId, CaptureRequest captureRequest);

  ReverseResponse reverseInTransaction(UUID authorisationId, ReverseRequest reverseRequest);

  AuthorisationResponse authoriseInTransaction(
      AuthorisationRequest request, String normalizedCurrency);
}
