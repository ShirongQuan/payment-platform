package org.example.auth.authorisation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
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
import org.springframework.test.context.jdbc.Sql;

@DataJpaTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.datasource.url=jdbc:h2:mem:authEventRepo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
    })
@Sql(scripts = "classpath:authorisation_event_repository_schema.sql")
class AuthorisationEventRepositoryTestH2 {

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

  @Test
  void shouldFindReversedEventByAccountIdIdempotencyKeyAndEventType() {
    UUID accountId = UUID.randomUUID();
    UUID authorisationId = UUID.randomUUID();
    String idempotencyKey = "reverse-key";
    setupAccountAndAuthorisation(accountId, authorisationId);

    repository.saveAndFlush(
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationId,
            accountId,
            EventType.AUTHORISATION_CAPTURED,
            "capture-key",
            new BigDecimal("10.00"),
            "USD",
            AuthorisationEventReason.NONE,
            UUID.randomUUID(),
            OffsetDateTime.now().minusSeconds(5)));

    AuthorisationEventEntity reversed =
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationId,
            accountId,
            EventType.AUTHORISATION_REVERSED,
            idempotencyKey,
            new BigDecimal("10.00"),
            "USD",
            AuthorisationEventReason.CUSTOMER_REQUEST,
            UUID.randomUUID(),
            OffsetDateTime.now());
    repository.saveAndFlush(reversed);

    var result =
        repository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, idempotencyKey, EventType.AUTHORISATION_REVERSED.toString());

    assertThat(result).isPresent();
    assertThat(result.get().getEventId()).isEqualTo(reversed.getEventId());
    assertThat(result.get().getEventType()).isEqualTo(EventType.AUTHORISATION_REVERSED);
    assertThat(result.get().getReasonCode()).isEqualTo(AuthorisationEventReason.CUSTOMER_REQUEST);
  }

  @Test
  void shouldReturnEmptyWhenReversedEventDoesNotExist() {
    UUID accountId = UUID.randomUUID();
    UUID authorisationId = UUID.randomUUID();
    setupAccountAndAuthorisation(accountId, authorisationId);

    repository.saveAndFlush(
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationId,
            accountId,
            EventType.AUTHORISATION_CAPTURED,
            "capture-key",
            new BigDecimal("10.00"),
            "USD",
            AuthorisationEventReason.NONE,
            UUID.randomUUID(),
            OffsetDateTime.now()));

    var result =
        repository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, "reverse-key", EventType.AUTHORISATION_REVERSED.toString());

    assertThat(result).isEmpty();
  }

  private void setupAccountAndAuthorisation(UUID accountId, UUID authorisationId) {
    OffsetDateTime now = OffsetDateTime.now().minusDays(1);

    AccountEntity account = newAccountEntity();
    account.setId(accountId);
    account.setStatus(AccountStatus.ACTIVE);
    account.setCurrencyCode("USD");
    account.setAvailableBalance(new BigDecimal("100.00"));
    account.setReservedBalance(BigDecimal.ZERO);
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
    accountRepository.saveAndFlush(account);

    AuthorisationEntity authorisation = newAuthorisationEntity();
    authorisation.setId(authorisationId);
    // Keep version null so Spring Data treats it as new and calls persist instead of merge.
    authorisation.setVersion(null);
    authorisation.setAccountId(accountId);
    authorisation.setAmount(new BigDecimal("10.00"));
    authorisation.setCurrencyCode("USD");
    authorisation.setMerchantReference("merchant-1");
    authorisation.setStatus(AuthorisationStatus.AUTHORISED);
    authorisation.setCreatedAt(now);
    authorisation.setUpdatedAt(now);
    authorisationRepository.saveAndFlush(authorisation);
  }

  private AccountEntity newAccountEntity() {
    try {
      Constructor<AccountEntity> constructor = AccountEntity.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to instantiate AccountEntity for test setup", e);
    }
  }

  private AuthorisationEntity newAuthorisationEntity() {
    try {
      Constructor<AuthorisationEntity> constructor = AuthorisationEntity.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to instantiate AuthorisationEntity for test setup", e);
    }
  }
}



