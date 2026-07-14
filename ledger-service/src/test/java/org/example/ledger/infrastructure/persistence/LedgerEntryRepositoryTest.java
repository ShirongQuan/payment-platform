package org.example.ledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
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
@Sql(scripts = "classpath:ledger_repository_schema.sql")
class LedgerEntryRepositoryTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("testdb")
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

  @Autowired private LedgerEntryRepository repository;
  @Autowired private EntityManager entityManager;

  @Test
  void shouldReturnAuthorisationsById() {
    UUID authorisationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    persistEntry(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        authorisationId,
        "AUTHORISATION",
        OffsetDateTime.parse("2026-07-14T09:00:00Z"));
    persistEntry(
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        authorisationId,
        "AUTHORISATION",
        OffsetDateTime.parse("2026-07-14T10:00:00Z"));
    persistEntry(
        UUID.fromString("33333333-3333-3333-3333-333333333333"),
        UUID.randomUUID(),
        "AUTHORISATION",
        OffsetDateTime.parse("2026-07-14T11:00:00Z"));

    entityManager.clear();

    var result = repository.findAuthorisationsById(authorisationId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getOccurredAt()).isAfter(result.get(1).getOccurredAt());
    assertThat(result).allMatch(e -> authorisationId.equals(e.getAggregateId()));
  }

  @Test
  void shouldReturnAccountEventsById() {
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    LedgerEntryEntity older =
        persistEntry(
            accountId,
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            "AUTHORISATION",
            OffsetDateTime.parse("2026-07-14T09:00:00Z"));
    LedgerEntryEntity newer =
        persistEntry(
            accountId,
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            "AUTHORISATION",
            OffsetDateTime.parse("2026-07-14T10:00:00Z"));

    entityManager.clear();

    var result = repository.findAccountEventsById(accountId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getEventId()).isEqualTo(newer.getEventId());
    assertThat(result.get(1).getEventId()).isEqualTo(older.getEventId());
  }

  private LedgerEntryEntity persistEntry(
      UUID accountId, UUID aggregateId, String aggregateType, OffsetDateTime occurredAt) {
    LedgerEntryEntity entity =
        new LedgerEntryEntity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            aggregateType,
            aggregateId,
            accountId,
            aggregateId,
            "AUTHORISATION_AUTHORISED",
            "AUTHORISED",
            new BigDecimal("10.00"),
            "GBP",
            "merchant",
            "idem-1",
            occurredAt,
            occurredAt,
            Map.of("status", "AUTHORISED"));

    return repository.saveAndFlush(entity);
  }
}
