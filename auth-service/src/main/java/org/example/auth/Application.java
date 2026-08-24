package org.example.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Auth service.
 *
 * <p>The Auth service owns accounts and the authorisation lifecycle (authorise/capture/reverse),
 * performs synchronous fraud checks, and reliably publishes resulting domain events via the
 * transactional outbox pattern (see {@link org.example.auth.outbox.application.OutboxScheduler}).
 *
 * <p>{@code @EnableScheduling} activates the outbox publisher's periodic polling job.
 */
@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    log.info("Starting Auth service application");
    SpringApplication.run(Application.class, args);
    log.info("Auth service application started");
  }
}
