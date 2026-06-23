package org.example.authservice.account.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.example.authservice.account.application.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing account management endpoints under {@code /accounts}.
 *
 * <p>Delegates all business logic to {@link AccountService}. Input validation is handled by Bean
 * Validation ({@code @Valid}); error responses are shaped by {@link org.example.authservice.common.exception.AuthExceptionHandler}.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {
  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  /** Creates a new account. Returns 200 with the created account details. */
  @PostMapping
  public AccountResponse createAccount(
      @RequestBody @NotNull @Valid CreateAccountRequest createAccountRequest) {
    return accountService.createAccount(createAccountRequest);
  }

  /** Fetches an account by its UUID. Returns 404 if not found. */
  @GetMapping("/{accountId}")
  public AccountResponse getAccountById(@PathVariable UUID accountId) {
    return accountService.getAccountById(accountId);
  }

  /** Deposits an amount into an account. Returns 400 on currency mismatch or invalid amount. */
  @PostMapping("/{accountId}/deposits")
  public AccountResponse deposit(
      @PathVariable UUID accountId, @RequestBody @NotNull @Valid DepositRequest depositRequest) {
    return accountService.deposit(accountId, depositRequest);
  }
}
