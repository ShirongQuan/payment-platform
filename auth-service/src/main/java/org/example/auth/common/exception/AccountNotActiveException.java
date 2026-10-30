package org.example.auth.common.exception;

import java.util.UUID;
import lombok.Getter;
import org.example.auth.account.domain.AccountStatus;

/**
 * Thrown when a mutating operation (deposit, reserve, capture, reverse) is attempted on an account
 * that is not {@link AccountStatus#ACTIVE} (e.g. LOCKED). Maps to HTTP 409.
 */
@Getter
public class AccountNotActiveException extends RuntimeException implements CodedException {

  private final UUID accountId;
  private final AccountStatus status;

  public AccountNotActiveException(UUID accountId, AccountStatus status) {
    super(String.format("Account %s is not active, current status=%s", accountId, status));
    this.accountId = accountId;
    this.status = status;
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.ACCOUNT_NOT_ACTIVE;
  }
}

