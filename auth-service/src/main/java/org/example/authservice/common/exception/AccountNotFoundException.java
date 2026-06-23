package org.example.authservice.common.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when a requested account ID does not exist in the database. Maps to HTTP 404. */
@Getter
public class AccountNotFoundException extends RuntimeException {
  private final UUID accountId;

  public AccountNotFoundException(UUID accountId) {
    super(String.format("Account not found for id=%s", accountId));

    this.accountId = accountId;
  }
}
