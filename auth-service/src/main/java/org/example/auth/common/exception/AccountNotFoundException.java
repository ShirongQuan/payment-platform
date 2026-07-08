package org.example.auth.common.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when a requested account ID does not exist in the database. Maps to HTTP 404. */
@Getter
public class AccountNotFoundException extends RuntimeException implements CodedException {
  private final UUID accountId;

  public AccountNotFoundException(UUID accountId) {
    super(String.format("Account not found for id=%s", accountId));

    this.accountId = accountId;
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.ACCOUNT_NOT_FOUND;
  }
}
