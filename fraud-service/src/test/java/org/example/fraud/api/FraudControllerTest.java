package org.example.fraud.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.example.fraud.application.FraudService;
import org.example.fraud.domain.FraudDecision;
import org.example.fraud.domain.RuleResult;
import org.example.fraud.exception.FraudEvaluationInProgressException;
import org.example.fraud.exception.FraudExceptionHandler;
import org.example.fraud.exception.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FraudController.class)
@Import(FraudExceptionHandler.class)
class FraudControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FraudService fraudService;

  @Test
  void shouldReturn200WhenFraudCheckCompleted() throws Exception {
    when(fraudService.check(any(FraudCheckRequest.class)))
        .thenReturn(
            new FraudCheckResponse(
                FraudDecision.APPROVE,
                40,
                List.of(new RuleResult("IP_VELOCITY_RULE", 40, "IP_VELOCITY_EXCEEDED in 30s")),
                false,
                null));

    mockMvc
        .perform(post("/fraud/check").contentType(MediaType.APPLICATION_JSON).content(validRequestJson()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.decision").value("APPROVE"))
        .andExpect(jsonPath("$.riskScore").value(40))
        .andExpect(jsonPath("$.reasons[0].ruleName").value("IP_VELOCITY_RULE"));
  }

  @Test
  void shouldReturn409WhenFraudCheckStillInProgress() throws Exception {
    when(fraudService.check(any(FraudCheckRequest.class)))
        .thenThrow(
            new FraudEvaluationInProgressException(
                UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "idem-key"));

    mockMvc
        .perform(post("/fraud/check").contentType(MediaType.APPLICATION_JSON).content(validRequestJson()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("FRAUD_EVALUATION_IN_PROGRESS"));
  }

  @Test
  void shouldReturn409WhenIdempotencyConflict() throws Exception {
    when(fraudService.check(any(FraudCheckRequest.class))).thenThrow(new IdempotencyConflictException());

    mockMvc
        .perform(post("/fraud/check").contentType(MediaType.APPLICATION_JSON).content(validRequestJson()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
  }

  private String validRequestJson() {
    return """
        {
          "accountId":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "idempotencyKey":"idem-key",
          "amount":10.00,
          "currencyCode":"GBP",
          "merchantReference":"merchant-ref",
          "ipAddress":"1.2.3.4"
        }
        """;
  }
}
