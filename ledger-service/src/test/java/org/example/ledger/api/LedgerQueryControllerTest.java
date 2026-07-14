package org.example.ledger.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.example.ledger.application.query.LedgerQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LedgerQueryController.class)
class LedgerQueryControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean
  private LedgerQueryService ledgerQueryService;

  @Test
  void shouldReturnEmptyListWhenAuthorisationNotFound() throws Exception {
    UUID authorisationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    when(ledgerQueryService.getAuthorisationsById(authorisationId)).thenReturn(List.of());

    mockMvc
        .perform(get("/authorisations/{authorisationId}", authorisationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldReturnAuthorisationFound() throws Exception {
    UUID authorisationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    AuthenticationResponse response =
        new AuthenticationResponse(
            authorisationId,
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "merchant-1",
            new BigDecimal("10.00"),
            "GBP",
            "AUTHORISED",
            OffsetDateTime.parse("2026-07-13T10:00:00Z"),
            UUID.fromString("99999999-9999-9999-9999-999999999999"));
    when(ledgerQueryService.getAuthorisationsById(authorisationId)).thenReturn(List.of(response));

    mockMvc
        .perform(get("/authorisations/{authorisationId}", authorisationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].authorisationId").value(authorisationId.toString()))
        .andExpect(jsonPath("$[0].currencyCode").value("GBP"))
        .andExpect(jsonPath("$[0].status").value("AUTHORISED"));
  }

  @Test
  void shouldReturn400IfAuthorisationIdParameterIsInvalid() throws Exception {
    mockMvc.perform(get("/authorisations/not-a-uuid")).andExpect(status().isBadRequest());
  }

  @Test
  void shouldReturnWhenEventsNotFound() throws Exception {
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(ledgerQueryService.getAccountEvents(accountId))
        .thenReturn(new AccountEventsResponse(accountId, List.of()));

    mockMvc
        .perform(get("/accounts/{accountId}/events", accountId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.events.length()").value(0));
  }

  @Test
  void shouldReturnWhenEventsFound() throws Exception {
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    AccountEvent event =
        new AccountEvent(
            UUID.fromString("99999999-9999-9999-9999-999999999999"),
            "AUTHORISATION_AUTHORISED",
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            OffsetDateTime.parse("2026-07-13T10:00:00Z"),
            new BigDecimal("10.00"),
            "GBP");
    when(ledgerQueryService.getAccountEvents(accountId))
        .thenReturn(new AccountEventsResponse(accountId, List.of(event)));

    mockMvc
        .perform(get("/accounts/{accountId}/events", accountId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.events[0].eventType").value("AUTHORISATION_AUTHORISED"));
  }

  @Test
  void shouldReturn400IfAccountIdParameterIsInvalid() throws Exception {
    mockMvc.perform(get("/accounts/not-a-uuid/events")).andExpect(status().isBadRequest());
  }
}
