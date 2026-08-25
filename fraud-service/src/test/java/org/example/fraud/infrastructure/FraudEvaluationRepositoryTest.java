package org.example.fraud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "classpath:fraud_evaluation_repository_schema.sql")
class FraudEvaluationRepositoryTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("fraud_test_db")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  @Autowired private FraudEvaluationRepository repository;

  @Test
  void shouldInsertPendingThenFinalize() {
    UUID evaluationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-key";

    int inserted =
        repository.tryInsertPending(
            evaluationId,
            accountId,
            new BigDecimal("12.50"),
            "GBP",
            "merchant-ref",
            idempotencyKey,
            "request-hash-1",
            "PENDING",
            "v1.0",
            "[]",
            "1.2.3.4",
            UUID.randomUUID(),
            OffsetDateTime.now());

    assertThat(inserted).isEqualTo(1);

    int finalized =
        repository.finalizeEvaluation(
            evaluationId,
            40,
            "APPROVE",
            """
            [{"ruleName":"IP_VELOCITY_RULE","score":40,"reason":"IP_VELOCITY_EXCEEDED in 30s"}]
            """,
            false,
            null);
    assertThat(finalized).isEqualTo(1);

    FraudEvaluationEntity saved =
        repository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey).orElseThrow();
    assertThat(saved.getDecision().name()).isEqualTo("APPROVE");
    assertThat(saved.getRiskScore()).isEqualTo(40);
    assertThat(saved.getRuleResult()).hasSize(1);
  }

  @Test
  void shouldReturnZeroWhenPendingInsertConflictsOnIdempotencyKey() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "dup-idem-key";
    OffsetDateTime now = OffsetDateTime.now();

    int firstInsert =
        repository.tryInsertPending(
            UUID.randomUUID(),
            accountId,
            new BigDecimal("50.00"),
            "GBP",
            "merchant-ref",
            idempotencyKey,
            "request-hash-1",
            "PENDING",
            "v1.0",
            "[]",
            "1.2.3.4",
            UUID.randomUUID(),
            now);

    int secondInsert =
        repository.tryInsertPending(
            UUID.randomUUID(),
            accountId,
            new BigDecimal("50.00"),
            "GBP",
            "merchant-ref",
            idempotencyKey,
            "request-hash-1",
            "PENDING",
            "v1.0",
            "[]",
            "1.2.3.4",
            UUID.randomUUID(),
            now.plusSeconds(1));

    assertThat(firstInsert).isEqualTo(1);
    assertThat(secondInsert).isEqualTo(0);
  }

  @Test
  void shouldCalculateAmountBaselineForApprovedTransactionsWithinWindow() {
    UUID accountId = UUID.randomUUID();
    insertApproved(accountId, "k1", "100.00", OffsetDateTime.now().minusDays(2));
    insertApproved(accountId, "k2", "200.00", OffsetDateTime.now().minusDays(10));
    insertDeclined(accountId, "k3", "900.00", OffsetDateTime.now().minusDays(1));
    insertApproved(accountId, "k4", "300.00", OffsetDateTime.now().minusDays(60));

    AmountBaseline baseline = repository.findAmountBaseline(accountId, "APPROVE", 30);

    assertThat(baseline.getTxnCount()).isEqualTo(2);
    assertThat(baseline.getAvgAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
  }

  private void insertApproved(UUID accountId, String key, String amount, OffsetDateTime createdAt) {
    UUID evaluationId = UUID.randomUUID();
    repository.tryInsertPending(
        evaluationId,
        accountId,
        new BigDecimal(amount),
        "GBP",
        "merchant-ref",
        key,
        "hash-" + key,
        "PENDING",
        "v1.0",
        "[]",
        "1.2.3.4",
        UUID.randomUUID(),
        createdAt);
    repository.finalizeEvaluation(evaluationId, 10, "APPROVE", "[]", false, null);
  }

  private void insertDeclined(UUID accountId, String key, String amount, OffsetDateTime createdAt) {
    UUID evaluationId = UUID.randomUUID();
    repository.tryInsertPending(
        evaluationId,
        accountId,
        new BigDecimal(amount),
        "GBP",
        "merchant-ref",
        key,
        "hash-" + key,
        "PENDING",
        "v1.0",
        "[]",
        "1.2.3.4",
        UUID.randomUUID(),
        createdAt);
    repository.finalizeEvaluation(evaluationId, 80, "DECLINE", "[]", false, null);
  }
}
