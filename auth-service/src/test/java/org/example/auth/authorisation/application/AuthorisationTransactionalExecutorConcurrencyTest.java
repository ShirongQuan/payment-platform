package org.example.auth.authorisation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountMapper;
import org.example.auth.account.infrastructure.AccountRepository;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AccountConcurrencyConflictException;
import org.example.auth.common.exception.InsufficientFundException;
import org.example.auth.fraud.FraudDecision;
import org.example.auth.outbox.application.OutboxEventService;
import org.example.auth.outbox.domain.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.datasource.url=jdbc:h2:mem:authConcurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "spring.task.scheduling.enabled=false"
    })
@Sql(scripts = "classpath:authorisation_transactional_executor_concurrency_schema.sql")
class AuthorisationTransactionalExecutorConcurrencyTest {

  @Autowired private AuthorisationTransactionalExecutor executor;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AuthorisationRepository authorisationRepository;
  @Autowired private AuthorisationEventRepository authorisationEventRepository;
  @Autowired private EntityManager entityManager;
  @MockitoSpyBean private AccountMapper accountMapper;
  @MockitoBean private OutboxEventService outboxEventService;

  @Test
  void shouldHandleConcurrentAuthorisationsOnSameAccountWithoutNegativeBalance() throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);

    List<InvocationResult<AuthorisationResponse>> results =
        runConcurrently(
            List.of(
                () ->
                    authoriseInTransaction(
                        new AuthorisationRequest(
                            accountId, "auth-key-1", new BigDecimal("80.00"), "USD", "m-1"),
                        "USD"),
                () ->
                    authoriseInTransaction(
                        new AuthorisationRequest(
                            accountId, "auth-key-2", new BigDecimal("80.00"), "USD", "m-2"),
                        "USD")));

    long authorisedCount =
        results.stream()
            .filter(InvocationResult::isSuccess)
            .map(InvocationResult::value)
            .filter(response -> response.status() == AuthorisationStatus.AUTHORISED)
            .count();

    long declinedCount =
        results.stream()
            .filter(InvocationResult::isSuccess)
            .map(InvocationResult::value)
            .filter(response -> response.status() == AuthorisationStatus.DECLINED)
            .count();

    long conflictOrRaceCount =
        results.stream()
            .filter(result -> !result.isSuccess())
            .filter(
                result ->
                    result.error() instanceof ObjectOptimisticLockingFailureException
                        || result.error() instanceof DataIntegrityViolationException
                        || result.error() instanceof ConcurrentIdempotencyRaceException
                        || result.error() instanceof AccountConcurrencyConflictException)
            .count();

    assertThat(authorisedCount).isEqualTo(1);
    assertThat(declinedCount + conflictOrRaceCount).isEqualTo(1);

    AccountEntity updatedAccount = accountRepository.findById(accountId).orElseThrow();
    assertThat(updatedAccount.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(updatedAccount.getReservedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(updatedAccount.getAvailableBalance().add(updatedAccount.getReservedBalance()))
        .isEqualByComparingTo("100.00");

  }

  @Test
  void shouldAllowOnlyOneConcurrentCaptureOnSameAuthorisation() throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);
    AuthorisationResponse authorisation =
        authoriseInTransaction(
            new AuthorisationRequest(
                accountId, "auth-capture-seed", new BigDecimal("10.00"), "USD", "capture-seed"),
            "USD");

    UUID authorisationId = authorisation.id();

    List<InvocationResult<CaptureResponse>> results =
        runConcurrently(
            List.of(
                () ->
                    executor.captureInTransaction(
                        authorisationId, new CaptureRequest("capture-key-1"), UUID.randomUUID()),
                () ->
                    executor.captureInTransaction(
                        authorisationId, new CaptureRequest("capture-key-2"), UUID.randomUUID())));

    long successCount = results.stream().filter(InvocationResult::isSuccess).count();
    long failureCount = results.stream().filter(result -> !result.isSuccess()).count();

    assertThat(successCount).isEqualTo(1);
    assertThat(failureCount).isEqualTo(1);

    AuthorisationEntity updated = authorisationRepository.findById(authorisationId).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(AuthorisationStatus.CAPTURED);

    AccountEntity updatedAccount = accountRepository.findById(accountId).orElseThrow();
    assertThat(updatedAccount.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(updatedAccount.getReservedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

    long captureEvents =
        authorisationEventRepository.findAll().stream()
            .filter(event -> event.getAuthorisationId().equals(authorisationId))
            .filter(event -> event.getEventType() == EventType.AUTHORISATION_CAPTURED)
            .count();
    assertThat(captureEvents).isEqualTo(1);
  }

  @Test
  void shouldAllowOnlyOneConcurrentReverseOnSameAuthorisation() throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);
    AuthorisationResponse authorisation =
        authoriseInTransaction(
            new AuthorisationRequest(
                accountId, "auth-reverse-seed", new BigDecimal("10.00"), "USD", "reverse-seed"),
            "USD");

    UUID authorisationId = authorisation.id();

    List<InvocationResult<ReverseResponse>> results =
        runConcurrently(
            List.of(
                () ->
                    executor.reverseInTransaction(
                        authorisationId,
                        new ReverseRequest("reverse-key-1", AuthorisationEventReason.CUSTOMER_REQUEST),
                        UUID.randomUUID()),
                () ->
                    executor.reverseInTransaction(
                        authorisationId,
                        new ReverseRequest("reverse-key-2", AuthorisationEventReason.CUSTOMER_REQUEST),
                        UUID.randomUUID())));

    long successCount = results.stream().filter(InvocationResult::isSuccess).count();
    long failureCount = results.stream().filter(result -> !result.isSuccess()).count();

    assertThat(successCount).isEqualTo(1);
    assertThat(failureCount).isEqualTo(1);

    AuthorisationEntity updated = authorisationRepository.findById(authorisationId).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(AuthorisationStatus.REVERSED);

    AccountEntity updatedAccount = accountRepository.findById(accountId).orElseThrow();
    assertThat(updatedAccount.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(updatedAccount.getReservedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

    long reverseEvents =
        authorisationEventRepository.findAll().stream()
            .filter(event -> event.getAuthorisationId().equals(authorisationId))
            .filter(event -> event.getEventType() == EventType.AUTHORISATION_REVERSED)
            .count();
    assertThat(reverseEvents).isEqualTo(1);
  }

  @Test
  void shouldPersistSingleAuthoriseEventForConcurrentSameIdempotencyKey() throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);

    String idempotencyKey = "auth-same-key";
    List<InvocationResult<AuthorisationResponse>> results =
        runConcurrently(
            List.of(
                () ->
                    authoriseInTransaction(
                        new AuthorisationRequest(
                            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1"),
                        "USD"),
                () ->
                    authoriseInTransaction(
                        new AuthorisationRequest(
                            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1"),
                        "USD")));

    long successCount = results.stream().filter(InvocationResult::isSuccess).count();
    long failureCount = results.stream().filter(result -> !result.isSuccess()).count();

    assertThat(successCount).isGreaterThanOrEqualTo(1);
    assertThat(failureCount).isLessThanOrEqualTo(1);
    assertOnlyExpectedErrors(results);

    long authoriseEvents =
        countEventsByAccountAndEventTypeAndIdempotencyKey(
            accountId, EventType.AUTHORISATION_AUTHORISED, idempotencyKey);
    assertThat(authoriseEvents).isEqualTo(1);
  }

  @Test
  void shouldPersistSingleCaptureEventForConcurrentSameIdempotencyKey() throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);
    AuthorisationResponse authorisation =
        authoriseInTransaction(
            new AuthorisationRequest(
                accountId, "auth-cap-same-seed", new BigDecimal("10.00"), "USD", "capture-seed"),
            "USD");

    UUID authorisationId = authorisation.id();
    String idempotencyKey = "capture-same-key";
    List<InvocationResult<CaptureResponse>> results =
        runConcurrently(
            List.of(
                () ->
                    executor.captureInTransaction(
                        authorisationId, new CaptureRequest(idempotencyKey), UUID.randomUUID()),
                () ->
                    executor.captureInTransaction(
                        authorisationId, new CaptureRequest(idempotencyKey), UUID.randomUUID())));

    long successCount = results.stream().filter(InvocationResult::isSuccess).count();
    assertThat(successCount).isGreaterThanOrEqualTo(1);
    assertOnlyExpectedErrors(results);

    long captureEvents =
        countEventsByAuthorisationAndEventTypeAndIdempotencyKey(
            authorisationId, EventType.AUTHORISATION_CAPTURED, idempotencyKey);
    assertThat(captureEvents).isEqualTo(1);
  }

  @Test
  void shouldPersistSingleReverseEventForConcurrentSameIdempotencyKey() throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);
    AuthorisationResponse authorisation =
        authoriseInTransaction(
            new AuthorisationRequest(
                accountId, "auth-rev-same-seed", new BigDecimal("10.00"), "USD", "reverse-seed"),
            "USD");

    UUID authorisationId = authorisation.id();
    String idempotencyKey = "reverse-same-key";
    List<InvocationResult<ReverseResponse>> results =
        runConcurrently(
            List.of(
                () ->
                    executor.reverseInTransaction(
                        authorisationId,
                        new ReverseRequest(idempotencyKey, AuthorisationEventReason.CUSTOMER_REQUEST),
                        UUID.randomUUID()),
                () ->
                    executor.reverseInTransaction(
                        authorisationId,
                        new ReverseRequest(idempotencyKey, AuthorisationEventReason.CUSTOMER_REQUEST),
                        UUID.randomUUID())));

    long successCount = results.stream().filter(InvocationResult::isSuccess).count();
    assertThat(successCount).isGreaterThanOrEqualTo(1);
    assertOnlyExpectedErrors(results);

    long reverseEvents =
        countEventsByAuthorisationAndEventTypeAndIdempotencyKey(
            authorisationId, EventType.AUTHORISATION_REVERSED, idempotencyKey);
    assertThat(reverseEvents).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldDetectStaleAuthorisationBeforeReportingInsufficientFunds(boolean reverse)
      throws Exception {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);
    UUID authorisationId =
        authoriseInTransaction(
                new AuthorisationRequest(
                    accountId, "stale-seed", new BigDecimal("10.00"), "USD", "merchant"),
                "USD")
            .id();
    CountDownLatch loaded = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    AtomicReference<Thread> delayedThread = new AtomicReference<>();
    doAnswer(
            invocation -> {
              if (Thread.currentThread() == delayedThread.get()) {
                loaded.countDown();
                if (!resume.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting for concurrent transition");
                }
                entityManager.refresh(invocation.getArgument(0));
              }
              return invocation.callRealMethod();
            })
        .when(accountMapper)
        .toAccount(any(AccountEntity.class));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> delayed =
          pool.submit(
              () -> {
                delayedThread.set(Thread.currentThread());
                assertThatThrownBy(() -> release(authorisationId, reverse))
                    .isInstanceOf(AccountConcurrencyConflictException.class)
                    .hasCauseInstanceOf(InsufficientFundException.class);
              });
      assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
      release(authorisationId, reverse);
      resume.countDown();
      delayed.get(10, TimeUnit.SECONDS);
    } finally {
      resume.countDown();
      pool.shutdownNow();
    }

    EventType eventType =
        reverse ? EventType.AUTHORISATION_REVERSED : EventType.AUTHORISATION_CAPTURED;
    assertThat(countEventsByAuthorisationAndEventTypeAndIdempotencyKey(
            authorisationId, eventType, "stale-key"))
        .isEqualTo(1);
    assertThat(accountRepository.findById(accountId).orElseThrow().getReservedBalance())
        .isEqualByComparingTo("0.00");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldPreserveInsufficientFundsForUnchangedAuthorisation(boolean reverse) {
    UUID accountId = UUID.randomUUID();
    setupAccount(accountId, new BigDecimal("100.00"), BigDecimal.ZERO);
    UUID authorisationId =
        authoriseInTransaction(
                new AuthorisationRequest(
                    accountId, "insufficient-seed", new BigDecimal("10.00"), "USD", "merchant"),
                "USD")
            .id();
    AccountEntity account = accountRepository.findById(accountId).orElseThrow();
    account.setReservedBalance(BigDecimal.ZERO);
    accountRepository.saveAndFlush(account);

    assertThatThrownBy(() -> release(authorisationId, reverse))
        .isInstanceOf(InsufficientFundException.class);
    assertThat(authorisationRepository.findById(authorisationId).orElseThrow().getStatus())
        .isEqualTo(AuthorisationStatus.AUTHORISED);
  }

  private void release(UUID authorisationId, boolean reverse) {
    if (reverse) {
      executor.reverseInTransaction(
          authorisationId,
          new ReverseRequest("stale-key", AuthorisationEventReason.CUSTOMER_REQUEST),
          UUID.randomUUID());
    } else {
      executor.captureInTransaction(
          authorisationId, new CaptureRequest("stale-key"), UUID.randomUUID());
    }
  }

  private <T> void assertOnlyExpectedErrors(List<InvocationResult<T>> results) {
    assertThat(results.stream().filter(result -> !result.isSuccess()))
        .allMatch(
            result ->
                result.error() instanceof ConcurrentIdempotencyRaceException
                    || result.error() instanceof ObjectOptimisticLockingFailureException
                    || result.error() instanceof DataIntegrityViolationException
                    || result.error() instanceof AccountConcurrencyConflictException);
  }

  private long countEventsByAuthorisationAndEventTypeAndIdempotencyKey(
      UUID authorisationId, EventType eventType, String idempotencyKey) {
    return authorisationEventRepository.findAll().stream()
        .filter(event -> event.getAuthorisationId().equals(authorisationId))
        .filter(event -> event.getEventType() == eventType)
        .filter(event -> event.getIdempotencyKey().equals(idempotencyKey))
        .count();
  }

  private long countEventsByAccountAndEventTypeAndIdempotencyKey(
      UUID accountId, EventType eventType, String idempotencyKey) {
    return authorisationEventRepository.findAll().stream()
        .filter(event -> event.getAccountId().equals(accountId))
        .filter(event -> event.getEventType() == eventType)
        .filter(event -> event.getIdempotencyKey().equals(idempotencyKey))
        .count();
  }

  private void setupAccount(UUID accountId, BigDecimal availableBalance, BigDecimal reservedBalance) {
    OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);
    AccountEntity account = newAccountEntity();
    account.setId(accountId);
    account.setVersion(null);
    account.setStatus(AccountStatus.ACTIVE);
    account.setCurrencyCode("USD");
    account.setAvailableBalance(availableBalance);
    account.setReservedBalance(reservedBalance);
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
    accountRepository.saveAndFlush(account);
  }

  private AuthorisationResponse authoriseInTransaction(
      AuthorisationRequest request, String normalizedCurrency) {
    return executor.authoriseInTransaction(
        request,
        normalizedCurrency,
        new PreAuthDecision.FraudEvaluated(FraudDecision.approve(0, java.util.List.of())),
        UUID.randomUUID());
  }

  private <T> List<InvocationResult<T>> runConcurrently(List<Callable<T>> operations) throws Exception {
    int parallelism = operations.size();
    ExecutorService executorService = Executors.newFixedThreadPool(parallelism);
    CountDownLatch ready = new CountDownLatch(parallelism);
    CountDownLatch start = new CountDownLatch(1);

    try {
      List<Future<InvocationResult<T>>> futures = new ArrayList<>();
      for (Callable<T> operation : operations) {
        futures.add(
            executorService.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    return InvocationResult.failure(new IllegalStateException("Start gate timeout"));
                  }
                  try {
                    return InvocationResult.success(operation.call());
                  } catch (Throwable throwable) {
                    return InvocationResult.failure(throwable);
                  }
                }));
      }

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<InvocationResult<T>> outcomes = new ArrayList<>(parallelism);
      for (Future<InvocationResult<T>> future : futures) {
        outcomes.add(future.get(10, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      executorService.shutdownNow();
    }
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

  private record InvocationResult<T>(T value, Throwable error) {
    static <T> InvocationResult<T> success(T value) {
      return new InvocationResult<>(value, null);
    }

    static <T> InvocationResult<T> failure(Throwable error) {
      return new InvocationResult<>(null, error);
    }

    boolean isSuccess() {
      return error == null;
    }
  }
}
