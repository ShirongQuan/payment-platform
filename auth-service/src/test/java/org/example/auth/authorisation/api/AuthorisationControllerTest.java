package org.example.auth.authorisation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.account.application.AccountService;
import org.example.auth.authorisation.application.AuthorisationService;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.common.OperationType;
import org.example.auth.common.exception.AuthExceptionHandler;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.CurrencyMismatchException;
import org.example.auth.common.exception.ErrorCode;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.common.exception.InsufficientFundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import(AuthExceptionHandler.class)
class AuthorisationControllerTest {
  @Autowired private MockMvc mockMVC;

  @MockitoBean private AuthorisationService authorisationService;

  @MockitoBean private AccountService accountService;

  @Test
  void shouldAuthoriseWithCorrectInput() throws Exception {
    UUID accountId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    UUID authorisationId = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now().minusDays(2);
    OffsetDateTime updatedAt = OffsetDateTime.now().minusDays(1);
    when(authorisationService.authorise(any(AuthorisationRequest.class), anyString()))
        .thenReturn(
            new AuthorisationResponse(
                authorisationId,
                accountId,
                "key",
                BigDecimal.TEN,
                "GBP",
                "reference",
                AuthorisationStatus.AUTHORISED,
                createdAt,
                updatedAt));

    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
"""
{
  "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "idempotencyKey": "key",
  "amount": 10.00,
  "currencyCode": "GBP",
  "merchantReference": "reference"
}
"""))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isNotEmpty())
        .andExpect(jsonPath("$.id").value(authorisationId.toString()))
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.amount").value(10.00))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.merchantReference").value("reference"))
        .andExpect(jsonPath("$.status").value(AuthorisationStatus.AUTHORISED.name()))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    verify(authorisationService, times(1)).authorise(any(AuthorisationRequest.class), anyString());
  }

  @Test
  void shouldReturnBadRequestWhenAuthoriseRequestCurrencyInvalid() throws Exception {
    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "idempotencyKey": "key",
                      "amount": 10.00,
                      "currencyCode": "ABCD",
                      "merchantReference": "reference"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(authorisationService, never()).authorise(any(AuthorisationRequest.class), anyString());
  }

  @Test
  void shouldReturnBadRequestWhenAuthoriseRequestAmountInvalid() throws Exception {
    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "idempotencyKey": "key",
                      "amount": 0.00,
                      "currencyCode": "GBP",
                      "merchantReference": "reference"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(authorisationService, never()).authorise(any(AuthorisationRequest.class), anyString());
  }

  @Test
  void shouldReturn404WhenAccountNotFoundDuringAuthorise() throws Exception {
    UUID accountId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    when(authorisationService.authorise(any(AuthorisationRequest.class), anyString()))
        .thenThrow(new AccountNotFoundException(accountId));

    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "idempotencyKey": "key",
                      "amount": 10.00,
                      "currencyCode": "GBP",
                      "merchantReference": "reference"
                    }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Account not found"))
        .andExpect(jsonPath("$.instance").value("/authorisations"))
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.ACCOUNT_NOT_FOUND.name()));
  }

  @Test
  void shouldReturn400WhenCurrencyMismatchDuringAuthorise() throws Exception {
    when(authorisationService.authorise(any(AuthorisationRequest.class), anyString()))
        .thenThrow(new CurrencyMismatchException("GBP", "USD"));

    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "idempotencyKey": "key",
                      "amount": 10.00,
                      "currencyCode": "USD",
                      "merchantReference": "reference"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Currency mismatch"))
        .andExpect(jsonPath("$.instance").value("/authorisations"))
        .andExpect(jsonPath("$.expectedCurrency").value("GBP"))
        .andExpect(jsonPath("$.providedCurrency").value("USD"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.CURRENCY_MISMATCH.name()));
  }

  @Test
  void shouldReturn400WhenInsufficientFundsDuringAuthorise() throws Exception {
    when(authorisationService.authorise(any(AuthorisationRequest.class), anyString()))
        .thenThrow(
            new InsufficientFundException(
                OperationType.AUTHORISE, BigDecimal.valueOf(5), BigDecimal.valueOf(10)));

    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "idempotencyKey": "key",
                      "amount": 10.00,
                      "currencyCode": "GBP",
                      "merchantReference": "reference"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Insufficient funds"))
        .andExpect(jsonPath("$.instance").value("/authorisations"))
        .andExpect(jsonPath("$.availableAmount").value(5.00))
        .andExpect(jsonPath("$.requestedAmount").value(10.00))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.INSUFFICIENT_FUNDS.name()));
  }

  @Test
  void shouldReturn409WhenIdempotencyConflictDuringAuthorise() throws Exception {
    when(authorisationService.authorise(any(AuthorisationRequest.class), anyString()))
        .thenThrow(new IdempotencyConflictException());

    mockMVC
        .perform(
            post("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "idempotencyKey": "key",
                      "amount": 10.00,
                      "currencyCode": "GBP",
                      "merchantReference": "reference"
                    }
                    """))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Idempotency key conflict"))
        .andExpect(jsonPath("$.instance").value("/authorisations"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.IDEMPOTENCY_CONFLICT.name()));
  }

  @Test
  void shouldGetAuthorisationIfExist() throws Exception {
    UUID accountId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    UUID authorisationId = UUID.randomUUID();
    when(authorisationService.getAuthorisationById(authorisationId))
        .thenReturn(
            new AuthorisationResponse(
                authorisationId,
                accountId,
                "key",
                BigDecimal.TEN,
                "GBP",
                "reference",
                AuthorisationStatus.AUTHORISED,
                OffsetDateTime.now().minusDays(2),
                OffsetDateTime.now().minusDays(1)));

    mockMVC
        .perform(get("/authorisations/{authorisationId}", authorisationId))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(authorisationId.toString()))
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.amount").value(10.00))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.merchantReference").value("reference"))
        .andExpect(jsonPath("$.status").value(AuthorisationStatus.AUTHORISED.name()));
  }

  @Test
  void shouldReturnNotFoundWhenAuthorisationNotExist() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationService.getAuthorisationById(authorisationId))
        .thenThrow(new AuthorisationNotFoundException(authorisationId));

    mockMVC
        .perform(get("/authorisations/{authorisationId}", authorisationId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Authorisation not found"))
        .andExpect(jsonPath("$.instance").value("/authorisations/" + authorisationId))
        .andExpect(jsonPath("$.authorisationId").value(authorisationId.toString()))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.AUTHORISATION_NOT_FOUND.name()));
  }

  @Test
  void shouldCaptureWithCorrectInput() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    OffsetDateTime updatedAt = OffsetDateTime.now().minusSeconds(1);

    when(authorisationService.capture(any(UUID.class), any(CaptureRequest.class)))
        .thenReturn(
            new CaptureResponse(
                authorisationId,
                "capture-key",
                BigDecimal.TEN,
                "GBP",
                AuthorisationStatus.CAPTURED,
                updatedAt));

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/captures", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "capture-key"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.authorisationId").value(authorisationId.toString()))
        .andExpect(jsonPath("$.idempotencyKey").value("capture-key"))
        .andExpect(jsonPath("$.capturedAmount").value(10.00))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.status").value(AuthorisationStatus.CAPTURED.name()))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    verify(authorisationService, times(1)).capture(any(UUID.class), any(CaptureRequest.class));
  }

  @Test
  void shouldReturnBadRequestWhenCaptureRequestIdempotencyInvalid() throws Exception {
    UUID authorisationId = UUID.randomUUID();

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/captures", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": ""
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(authorisationService, never()).capture(any(UUID.class), any(CaptureRequest.class));
  }

  @Test
  void shouldReturnNotFoundWhenCaptureAuthorisationNotFound() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationService.capture(any(UUID.class), any(CaptureRequest.class)))
        .thenThrow(new AuthorisationNotFoundException(authorisationId));

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/captures", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "capture-key"
                    }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.AUTHORISATION_NOT_FOUND.name()));
  }

  @Test
  void shouldReturnConflictWhenCaptureIdempotencyConflict() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationService.capture(any(UUID.class), any(CaptureRequest.class)))
        .thenThrow(new IdempotencyConflictException());

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/captures", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "capture-key"
                    }
                    """))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.IDEMPOTENCY_CONFLICT.name()));
  }

  @Test
  void shouldReverseWithCorrectInput() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    OffsetDateTime updatedAt = OffsetDateTime.now().minusSeconds(1);

    when(authorisationService.reverse(any(UUID.class), any(ReverseRequest.class)))
        .thenReturn(
            new ReverseResponse(
                authorisationId,
                "reverse-key",
                BigDecimal.TEN,
                "GBP",
                AuthorisationStatus.REVERSED,
                AuthorisationEventReason.CUSTOMER_REQUEST,
                updatedAt));

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/reversals", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "reverse-key",
                      "reasonCode": "CUSTOMER_REQUEST"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.authorisationId").value(authorisationId.toString()))
        .andExpect(jsonPath("$.idempotencyKey").value("reverse-key"))
        .andExpect(jsonPath("$.reversedAmount").value(10.00))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.status").value(AuthorisationStatus.REVERSED.name()))
        .andExpect(jsonPath("$.reasonCode").value("CUSTOMER_REQUEST"))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    verify(authorisationService, times(1)).reverse(any(UUID.class), any(ReverseRequest.class));
  }

  @Test
  void shouldReturnBadRequestWhenReverseRequestInvalid() throws Exception {
    UUID authorisationId = UUID.randomUUID();

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/reversals", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "",
                      "reasonCode": ""
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    verify(authorisationService, never()).reverse(any(UUID.class), any(ReverseRequest.class));
  }

  @Test
  void shouldReturnNotFoundWhenReverseAuthorisationNotFound() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationService.reverse(any(UUID.class), any(ReverseRequest.class)))
        .thenThrow(new AuthorisationNotFoundException(authorisationId));

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/reversals", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "reverse-key",
                      "reasonCode": "CUSTOMER_REQUEST"
                    }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.AUTHORISATION_NOT_FOUND.name()));
  }

  @Test
  void shouldReturnConflictWhenReverseIdempotencyConflict() throws Exception {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationService.reverse(any(UUID.class), any(ReverseRequest.class)))
        .thenThrow(new IdempotencyConflictException());

    mockMVC
        .perform(
            post("/authorisations/{authorisationId}/reversals", authorisationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "idempotencyKey": "reverse-key",
                      "reasonCode": "CUSTOMER_REQUEST"
                    }
                    """))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCode.IDEMPOTENCY_CONFLICT.name()));
  }
}
