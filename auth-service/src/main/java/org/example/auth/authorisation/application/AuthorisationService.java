package org.example.auth.authorisation.application;

import java.util.UUID;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;

/** Application use-case contract for authorise/capture/reverse operations. */
public interface AuthorisationService {
  AuthorisationResponse authorise(AuthorisationRequest request);

  AuthorisationResponse getAuthorisationById(UUID authorisationId);

  CaptureResponse capture(UUID authorisationId, CaptureRequest captureRequest);

  ReverseResponse reverse(UUID authorisationId, ReverseRequest reverseRequest);
}
