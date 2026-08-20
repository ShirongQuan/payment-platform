package org.example.auth.account.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.example.auth.account.application.AccountService;
import org.example.auth.account.domain.Account;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.common.exception.AuthExceptionHandler;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@Import(AuthExceptionHandler.class)
class AccountControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccountService accountService;

  @Test
  void shouldNotCreateAccountWithInvalidCurrency() throws Exception {
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"currencyCode":"abc"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(accountService, never()).createAccount(any(CreateAccountRequest.class));
  }

  @Test
  void shouldCreateAccountWithCorrectCurrency() throws Exception {
    Account account = new Account("GBP");
    when(accountService.createAccount(any(CreateAccountRequest.class)))
        .thenReturn(
            new AccountResponse(
                account.getId(),
                AccountStatus.ACTIVE,
                account.getCurrencyCode(),
                account.getAvailableBalance(),
                account.getReservedBalance(),
                account.getCreatedAt(),
                account.getUpdatedAt()));

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"currencyCode":"GBP"}
                    """))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.accountId").value(account.getId().toString()))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.status").value(AccountStatus.ACTIVE.name()))
        .andExpect(jsonPath("$.availableBalance").value("0"))
        .andExpect(jsonPath("$.reservedBalance").value("0"));
  }

  @Test
  void shouldReturn404WhenAccountNotFound() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(accountService.getAccountById(accountId))
        .thenThrow(new AccountNotFoundException(accountId));

    mockMvc
        .perform(get("/accounts/{accountId}", accountId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Account not found"))
        .andExpect(jsonPath("$.instance").value("/accounts/" + accountId))
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.ACCOUNT_NOT_FOUND.name()));
  }

  @Test
  void shouldReturn200WhenAccountFound() throws Exception {
    Account account = new Account("GBP");
    when(accountService.getAccountById(account.getId()))
        .thenReturn(
            new AccountResponse(
                account.getId(),
                account.getStatus(),
                account.getCurrencyCode(),
                account.getAvailableBalance(),
                account.getReservedBalance(),
                account.getCreatedAt(),
                account.getUpdatedAt()));

    mockMvc
        .perform(get("/accounts/{accountId}", account.getId()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isNotEmpty())
        .andExpect(jsonPath("$.accountId").value(account.getId().toString()));
  }

  @Test
  void shouldReturnErrorWithInvalidDepositRequest() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/accounts/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amount":0,"currencyCode":"GBP"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(accountService, never()).deposit(any(UUID.class), any(DepositRequest.class));
  }

  @Test
  void shouldReturn200WhenDepositSucceed() throws Exception {
    Account account = new Account("GBP");
    when(accountService.deposit(eq(account.getId()), any(DepositRequest.class)))
        .thenReturn(
            new AccountResponse(
                account.getId(),
                AccountStatus.ACTIVE,
                account.getCurrencyCode(),
                BigDecimal.TEN,
                account.getReservedBalance(),
                account.getCreatedAt(),
                account.getUpdatedAt()));

    String content =
        """
        {"amount":10.00, "currencyCode":"GBP"}
        """;

    mockMvc
        .perform(
            post("/accounts/{accountId}/deposits", account.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isNotEmpty())
        .andExpect(jsonPath("$.accountId").value(account.getId().toString()))
        .andExpect(jsonPath("$.availableBalance").value(BigDecimal.TEN.toString()));

    verify(accountService).deposit(eq(account.getId()), any(DepositRequest.class));
  }

  @Test
  void shouldReturn404WhenDepositAccountNotFound() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(accountService.deposit(eq(accountId), any(DepositRequest.class)))
        .thenThrow(new AccountNotFoundException(accountId));

    mockMvc
        .perform(
            post("/accounts/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amount":10.00, "currencyCode":"GBP"}
                    """))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Account not found"))
        .andExpect(jsonPath("$.instance").value("/accounts/" + accountId + "/deposits"))
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.ACCOUNT_NOT_FOUND.name()));
  }

  @Test
  void shouldReturn400WhenDepositCurrencyIsInvalid() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/accounts/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amount":10.00,"currencyCode":"abc"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(accountService, never()).deposit(any(UUID.class), any(DepositRequest.class));
  }
}
