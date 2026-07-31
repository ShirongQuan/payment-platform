package org.example.auth.authorisation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountRepository;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.outbox.domain.EventType;
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
@Sql(scripts = "classpath:authorisation_event_repository_schema.sql")
class AuthorisationEventRepositoryTest {

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

  @Autowired private AuthorisationEventRepository repository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AuthorisationRepository authorisationRepository;

  @Test
  void shouldFindCapturedEventByAccountIdIdempotencyKeyAndEventType() {
    UUID accountId = UUID.randomUUID();
    UUID authorisationId = UUID.randomUUID();
    String idempotencyKey = "capture-key";
    setupAccountAndAuthorisation(accountId, authorisationId);

    repository.saveAndFlush(
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationId,
            accountId,
            EventType.AUTHORISATION_AUTHORISED,
            idempotencyKey,
            new BigDecimal("10.00"),
            "USD",
            AuthorisationEventReason.NONE,
            UUID.randomUUID(),
            OffsetDateTime.now().minusSeconds(5)));

    AuthorisationEventEntity captured =
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationId,
            accountId,
            EventType.AUTHORISATION_CAPTURED,
            idempotencyKey,
            new BigDecimal("10.00"),
            "USD",
            AuthorisationEventReason.NONE,
            UUID.randomUUID(),
            OffsetDateTime.now());
    repository.saveAndFlush(captured);

    var result =
        repository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, idempotencyKey, EventType.AUTHORISATION_CAPTURED.toString());

    assertThat(result).isPresent();
    assertThat(result.get().getEventId()).isEqualTo(captured.getEventId());
    assertThat(result.get().getEventType()).isEqualTo(EventType.AUTHORISATION_CAPTURED);
  }

  @Test
  void shouldReturnEmptyWhenCapturedEventDoesNotExist() {
    UUID accountId = UUID.randomUUID();
    UUID authorisationId = UUID.randomUUID();
    setupAccountAndAuthorisation(accountId, authorisationId);

    repository.saveAndFlush(
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationId,
            accountId,
            EventType.AUTHORISATION_AUTHORISED,
            "authorise-key",
            new BigDecimal("10.00"),
            "USD",
            AuthorisationEventReason.NONE,
            UUID.randomUUID(),
            OffsetDateTime.now()));

    var result =
        repository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, "capture-key", EventType.AUTHORISATION_CAPTURED.toString());

    assertThat(result).isEmpty();
  }

  private void setupAccountAndAuthorisation(UUID accountId, UUID authorisationId) {
    OffsetDateTime now = OffsetDateTime.now().minusDays(1);

    AccountEntity account = new TestAccountEntity();
    account.setId(accountId);
    account.setStatus(AccountStatus.ACTIVE);
    account.setCurrencyCode("USD");
    account.setAvailableBalance(new BigDecimal("100.00"));
    account.setReservedBalance(BigDecimal.ZERO);
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
    accountRepository.saveAndFlush(account);

    AuthorisationEntity authorisation =
        new AuthorisationEntity(
            authorisationId,
            0L,
            accountId,
            new BigDecimal("10.00"),
            "USD",
            "merchant-1",
            AuthorisationStatus.AUTHORISED,
            now,
            now);
    authorisationRepository.saveAndFlush(authorisation);
  }

  private static class TestAccountEntity extends AccountEntity {}
}
