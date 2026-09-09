package org.example.auth.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.EventType;
import org.example.auth.outbox.domain.OutboxEvent;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
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
@Sql(scripts = "classpath:outbox_repository_schema.sql")
class OutboxEventRepositoryTest {

  private static final OutboxEventMapper outboxEventMapper =
      Mappers.getMapper(OutboxEventMapper.class);

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

  @Autowired private OutboxEventRepository repository;
  @Autowired private EntityManager entityManager;

  @Test
  void reclaimStalePublishingShouldReclaimOnlyExpiredPublishing() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T10:00:00Z");

    UUID expiredId =
        persistEvent(
            OutboxEventStatus.PUBLISHING,
            1,
            "in-flight",
            now.minusMinutes(10),
            now.minusMinutes(2),
            now.minusSeconds(10),
            null);

    UUID activePublishingId =
        persistEvent(
            OutboxEventStatus.PUBLISHING,
            1,
            "in-flight",
            now.minusMinutes(5),
            now.minusSeconds(20),
            now.plusSeconds(30),
            null);

    int reclaimed =
        repository.reclaimStalePublishing(now, OutboxEventStatus.NEW, OutboxEventStatus.PUBLISHING);

    assertThat(reclaimed).isEqualTo(1);

    entityManager.clear();
    OutboxEventEntity expired = repository.findById(expiredId).orElseThrow();
    assertThat(expired.getStatus()).isEqualTo(OutboxEventStatus.NEW);
    assertThat(expired.getNextAttemptAt()).isEqualTo(now);
    assertThat(expired.getClaimedAt()).isNull();
    assertThat(expired.getClaimUntil()).isNull();

    OutboxEventEntity activePublishing = repository.findById(activePublishingId).orElseThrow();
    assertThat(activePublishing.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHING);
    assertThat(activePublishing.getClaimUntil()).isAfter(now);
  }

  @Test
  void claimNewEventShouldClaimOnlyNewStatus() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T11:00:00Z");
    OffsetDateTime claimUntil = now.plusSeconds(30);

    UUID newId =
        persistEvent(OutboxEventStatus.NEW, 0, null, now.minusMinutes(1), null, null, null);
    UUID failedId =
        persistEvent(OutboxEventStatus.FAILED, 3, "failed", now.minusMinutes(1), null, null, null);

    int claimedNew =
        repository.claimNewEvent(
            newId, now, claimUntil, OutboxEventStatus.PUBLISHING, OutboxEventStatus.NEW);
    int claimedFailed =
        repository.claimNewEvent(
            failedId, now, claimUntil, OutboxEventStatus.PUBLISHING, OutboxEventStatus.NEW);

    assertThat(claimedNew).isEqualTo(1);
    assertThat(claimedFailed).isEqualTo(0);

    entityManager.clear();
    OutboxEventEntity claimed = repository.findById(newId).orElseThrow();
    assertThat(claimed.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHING);
    assertThat(claimed.getClaimedAt()).isEqualTo(now);
    assertThat(claimed.getClaimUntil()).isEqualTo(claimUntil);
  }

  @Test
  void markPublishedShouldUpdateStatusAndClearClaimFields() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T12:00:00Z");
    OffsetDateTime publishedAt = now.plusSeconds(5);
    OffsetDateTime claimedAt = now.minusSeconds(20);

    UUID id =
        persistEvent(
            OutboxEventStatus.PUBLISHING,
            1,
            "previous error",
            now.minusMinutes(1),
            claimedAt,
            now.plusSeconds(20),
            null);

    int updated =
        repository.markPublished(
            id, publishedAt, OutboxEventStatus.PUBLISHED, OutboxEventStatus.PUBLISHING, claimedAt);

    assertThat(updated).isEqualTo(1);
    entityManager.clear();
    OutboxEventEntity event = repository.findById(id).orElseThrow();
    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
    assertThat(event.getLastError()).isNull();
    assertThat(event.getClaimedAt()).isNull();
    assertThat(event.getClaimUntil()).isNull();
  }

  @Test
  void markPublishedShouldNotUpdate_whenClaimedAtDoesNotMatchCurrentClaim() {
    // Simulates a stale completion arriving after the lease expired and the row was reclaimed
    // and re-claimed by a newer attempt with a different claimedAt (fencing token).
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T12:30:00Z");
    OffsetDateTime staleClaimedAt = now.minusMinutes(5);
    OffsetDateTime currentClaimedAt = now.minusSeconds(5);

    UUID id =
        persistEvent(
            OutboxEventStatus.PUBLISHING,
            0,
            null,
            now.minusMinutes(1),
            currentClaimedAt,
            now.plusSeconds(25),
            null);

    int updated =
        repository.markPublished(
            id,
            now,
            OutboxEventStatus.PUBLISHED,
            OutboxEventStatus.PUBLISHING,
            staleClaimedAt);

    assertThat(updated).isEqualTo(0);
    entityManager.clear();
    OutboxEventEntity event = repository.findById(id).orElseThrow();
    // Row is untouched by the stale completion; the current (newer) claim is preserved.
    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHING);
    assertThat(event.getClaimedAt()).isEqualTo(currentClaimedAt);
  }

  @Test
  void markRetryShouldUpdateStatusAndClearClaimFields() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T13:00:00Z");
    OffsetDateTime nextAttempt = now.plusMinutes(5);
    OffsetDateTime claimedAt = now.minusSeconds(30);

    UUID id =
        persistEvent(
            OutboxEventStatus.PUBLISHING,
            1,
            "temp",
            now.minusMinutes(1),
            claimedAt,
            now.plusSeconds(30),
            null);

    int updated =
        repository.markRetry(
            id,
            2,
            nextAttempt,
            "retryable",
            OutboxEventStatus.NEW,
            OutboxEventStatus.PUBLISHING,
            claimedAt);

    assertThat(updated).isEqualTo(1);
    entityManager.clear();
    OutboxEventEntity event = repository.findById(id).orElseThrow();
    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
    assertThat(event.getRetryCount()).isEqualTo(2);
    assertThat(event.getNextAttemptAt()).isEqualTo(nextAttempt);
    assertThat(event.getLastError()).isEqualTo("retryable");
    assertThat(event.getClaimedAt()).isNull();
    assertThat(event.getClaimUntil()).isNull();
  }

  @Test
  void markFailedShouldUpdateStatusAndClearClaimFields() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T14:00:00Z");
    OffsetDateTime claimedAt = now.minusSeconds(30);

    UUID id =
        persistEvent(
            OutboxEventStatus.PUBLISHING,
            2,
            "temp",
            now.minusMinutes(1),
            claimedAt,
            now.plusSeconds(30),
            null);

    int updated =
        repository.markFailed(
            id, 3, "fatal", OutboxEventStatus.FAILED, OutboxEventStatus.PUBLISHING, claimedAt);

    assertThat(updated).isEqualTo(1);
    entityManager.clear();
    OutboxEventEntity event = repository.findById(id).orElseThrow();
    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    assertThat(event.getRetryCount()).isEqualTo(3);
    assertThat(event.getLastError()).isEqualTo("fatal");
    assertThat(event.getClaimedAt()).isNull();
    assertThat(event.getClaimUntil()).isNull();
  }

  @Test
  void markPublishedShouldNotUpdateWhenStatusIsNotPublishing() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T15:00:00Z");
    OffsetDateTime publishedAt = now.plusSeconds(5);

    UUID id =
        persistEvent(OutboxEventStatus.NEW, 0, null, now.minusMinutes(1), null, null, null);

    int updated =
        repository.markPublished(
            id, publishedAt, OutboxEventStatus.PUBLISHED, OutboxEventStatus.PUBLISHING, null);

    assertThat(updated).isEqualTo(0);
  }

  @Test
  void markRetryShouldNotUpdateWhenStatusIsNotPublishing() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T16:00:00Z");
    OffsetDateTime nextAttempt = now.plusMinutes(2);

    UUID id =
        persistEvent(OutboxEventStatus.NEW, 0, null, now.minusMinutes(1), null, null, null);

    int updated =
        repository.markRetry(
            id,
            1,
            nextAttempt,
            "retryable",
            OutboxEventStatus.NEW,
            OutboxEventStatus.PUBLISHING,
            null);

    assertThat(updated).isEqualTo(0);
  }

  @Test
  void markFailedShouldNotUpdateWhenStatusIsNotPublishing() {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-08T17:00:00Z");

    UUID id =
        persistEvent(OutboxEventStatus.NEW, 0, null, now.minusMinutes(1), null, null, null);

    int updated =
        repository.markFailed(
            id, 1, "fatal", OutboxEventStatus.FAILED, OutboxEventStatus.PUBLISHING, null);

    assertThat(updated).isEqualTo(0);
  }

  private UUID persistEvent(
      OutboxEventStatus status,
      int retryCount,
      String lastError,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime claimedAt,
      OffsetDateTime claimUntil,
      OffsetDateTime publishedAt) {
    UUID id = UUID.randomUUID();
    OutboxEvent event =
        new OutboxEvent(
            id,
            AggregateType.AUTHORISATION,
            UUID.randomUUID(),
            EventType.AUTHORISATION_AUTHORISED,
            Map.of("amount", 10, "currencyCode", "USD"),
            status,
            retryCount,
            lastError,
            OffsetDateTime.parse("2026-07-08T09:00:00Z"),
            nextAttemptAt,
            claimedAt,
            claimUntil,
            publishedAt,
            "idem-" + id.toString().substring(0, 8),
            UUID.randomUUID(),
            null);

    repository.saveAndFlush(outboxEventMapper.toEntity(event));
    return id;
  }
}
