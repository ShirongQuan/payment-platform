package org.example.ledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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

@DataJpaTest
//@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "classpath:ledger_repository_schema.sql")
class ProcessedEventRepositoryTest {

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

  @Autowired private ProcessedEventRepository repository;

  @Test
  void shouldInsertProcessEventWhenNoConflict() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    int inserted =
        repository.tryInsertProcessedEvent(
            eventId, "AUTHORISATION_AUTHORISED", OffsetDateTime.parse("2026-07-14T09:00:00Z"));

    assertThat(inserted).isEqualTo(1);
    assertThat(repository.findById(eventId)).isPresent();
  }

  @Test
  void shouldDoNothingWhenEventExist() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    int first =
        repository.tryInsertProcessedEvent(
            eventId, "AUTHORISATION_AUTHORISED", OffsetDateTime.parse("2026-07-14T09:00:00Z"));
    int second =
        repository.tryInsertProcessedEvent(
            eventId, "AUTHORISATION_AUTHORISED", OffsetDateTime.parse("2026-07-14T09:01:00Z"));

    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(0);
    assertThat(repository.count()).isEqualTo(1);
  }
}
