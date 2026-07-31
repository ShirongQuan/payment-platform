package org.example.auth.common.exception;

import org.example.auth.authorisation.domain.AuthorisationStatus;

import java.util.UUID;

public class AuthorisationIllegalStateException extends RuntimeException {

  public AuthorisationIllegalStateException(
      UUID authorisationId, AuthorisationStatus currentStatus, String attemptedAction) {
    super(
        "Cannot %s authorisation %s because current status is %s"
            .formatted(attemptedAction, authorisationId, currentStatus));
  }
}
