package org.example.auth.common.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when a requested authorisation ID does not exist in the database. Maps to HTTP 404. */
@Getter
public class AuthorisationNotFoundException extends RuntimeException implements CodedException {

  private final UUID authorisationId;

  public AuthorisationNotFoundException(UUID authorisationId) {
    super(String.format("Authorisation with id=%s not found", authorisationId));
    this.authorisationId = authorisationId;
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.AUTHORISATION_NOT_FOUND;
  }
}
