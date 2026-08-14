package org.example.fraud.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.example.fraud.infrastructure.FraudEvaluationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "risk.rules.velocity.ip.enabled=false",
      "risk.rules.velocity.account.enabled=false",
      "risk.rules.amount-deviation.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FraudCheckFlowIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("fraud_integration_db")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private FraudEvaluationRepository repository;

  @Test
  void shouldCompleteFraudCheckAndReturnStoredResultOnDuplicateRetry() throws Exception {
    UUID accountId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    String idempotencyKey = "full-flow-key";
    String request =
        """
        {
          "accountId":"%s",
          "idempotencyKey":"%s",
          "amount":100.00,
          "currencyCode":"GBP",
          "merchantReference":"merchant-ref",
          "ipAddress":"1.2.3.4",
          "correlationId":"f190f5de-22aa-4ef6-b0f4-541f45e8451e"
        }
        """
            .formatted(accountId, idempotencyKey);

    mockMvc
        .perform(post("/fraud/check").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.decision").value("APPROVE"))
        .andExpect(jsonPath("$.riskScore").value(0))
        .andExpect(jsonPath("$.reasons").isArray());

    mockMvc
        .perform(post("/fraud/check").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("APPROVE"))
        .andExpect(jsonPath("$.riskScore").value(0));

    repository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey).orElseThrow();
  }
}
