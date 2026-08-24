package org.example.auth.common.exception;

import java.util.UUID;
import org.example.auth.authorisation.domain.AuthorisationStatus;

/**
 * Thrown when an authorisation lifecycle operation (capture/reverse) is attempted while the
 * authorisation is not in a valid status for that operation. Maps to HTTP 409.
 */
public class AuthorisationIllegalStateException extends RuntimeException implements CodedException {

  public AuthorisationIllegalStateException(
      UUID authorisationId, AuthorisationStatus currentStatus, String attemptedAction) {
    super(
        "Cannot %s authorisation %s because current status is %s"
            .formatted(attemptedAction, authorisationId, currentStatus));
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.INVALID_AUTHORISATION_STATE;
  }
}
