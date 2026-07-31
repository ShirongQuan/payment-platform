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
import org.springframework.test.context.jdbc.Sql;

@DataJpaTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.datasource.url=jdbc:h2:mem:ledgerRepo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
    })
@Sql(scripts = "classpath:ledger_repository_schema.sql")
class LedgerEntryRepositoryTestH2 {

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
    persistEntry(
        UUID.fromString("44444444-4444-4444-4444-444444444444"),
        authorisationId,
        "ACCOUNT",
        OffsetDateTime.parse("2026-07-14T12:00:00Z"));

    entityManager.clear();

    var result = repository.findAuthorisationsById(authorisationId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getOccurredAt()).isAfter(result.get(1).getOccurredAt());
    assertThat(result).allMatch(e -> "AUTHORISATION".equals(e.getAggregateType()));
    assertThat(result).allMatch(e -> authorisationId.equals(e.getAggregateId()));
  }

  @Test
  void shouldReturnEmptyListWhenNoAuthorisationFoundById() {
    var result = repository.findAuthorisationsById(UUID.randomUUID());
    assertThat(result).isEmpty();
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
    persistEntry(
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
        "AUTHORISATION",
        OffsetDateTime.parse("2026-07-14T11:00:00Z"));

    entityManager.clear();

    var result = repository.findAccountEventsById(accountId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getEventId()).isEqualTo(newer.getEventId());
    assertThat(result.get(1).getEventId()).isEqualTo(older.getEventId());
  }

  @Test
  void shouldReturnEmptyListWhenNoAccountEventsFoundByAccountId() {
    var result = repository.findAccountEventsById(UUID.randomUUID());
    assertThat(result).isEmpty();
  }

  @Test
  void shouldIncludeCapturedEntriesWhenQueryingAuthorisationTimeline() {
    UUID authorisationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    persistEntry(
        accountId,
        authorisationId,
        "AUTHORISATION",
        "AUTHORISATION_AUTHORISED",
        "AUTHORISED",
        OffsetDateTime.parse("2026-07-14T09:00:00Z"));
    persistEntry(
        accountId,
        authorisationId,
        "AUTHORISATION",
        "AUTHORISATION_CAPTURED",
        "CAPTURED",
        OffsetDateTime.parse("2026-07-14T10:00:00Z"));

    entityManager.clear();

    var result = repository.findAuthorisationsById(authorisationId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getEventType()).isEqualTo("AUTHORISATION_CAPTURED");
    assertThat(result.get(1).getEventType()).isEqualTo("AUTHORISATION_AUTHORISED");
  }

  private LedgerEntryEntity persistEntry(
      UUID accountId, UUID aggregateId, String aggregateType, OffsetDateTime occurredAt) {
    return persistEntry(
        accountId, aggregateId, aggregateType, "AUTHORISATION_AUTHORISED", "AUTHORISED", occurredAt);
  }

  private LedgerEntryEntity persistEntry(
      UUID accountId,
      UUID aggregateId,
      String aggregateType,
      String eventType,
      String entryStatus,
      OffsetDateTime occurredAt) {
    LedgerEntryEntity entity =
        new LedgerEntryEntity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            aggregateType,
            aggregateId,
            accountId,
            aggregateId,
            eventType,
            entryStatus,
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
