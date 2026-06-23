package org.example.authservice.account.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.example.authservice.account.api.AccountResponse;
import org.example.authservice.account.api.CreateAccountRequest;
import org.example.authservice.account.api.DepositRequest;

/**
 * Application service defining account management operations.
 *
 * <p>Implementations are responsible for orchestrating domain logic, transaction boundaries,
 * and persistence. All parameters are validated via Bean Validation before execution.
 */
public interface AccountService {

  /**
   * Creates a new account with the given currency and zero balances.
   *
   * @param createAccountRequest must contain a valid ISO 4217 currency code
   * @return a summary of the newly created account
   */
  AccountResponse createAccount(@Valid CreateAccountRequest createAccountRequest);

  /**
   * Retrieves an account by its unique ID.
   *
   * @param accountId the UUID of the account to look up
   * @return account details
   * @throws org.example.authservice.common.exception.AccountNotFoundException if no account exists with the given ID
   */
  AccountResponse getAccountById(UUID accountId);

  /**
   * Deposits funds into an account, increasing the available balance.
   *
   * @param accountId     the target account
   * @param depositRequest must specify a positive amount and matching currency code
   * @return updated account details including the new balance
   * @throws org.example.authservice.common.exception.AccountNotFoundException if the account does not exist
   * @throws org.example.authservice.common.exception.CurrencyMismatchException if the deposit currency differs from the account currency
   */
  AccountResponse deposit(UUID accountId, @NotNull @Valid DepositRequest depositRequest);
}
