package org.example.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Ledger service.
 *
 * <p>The Ledger service consumes authorisation lifecycle events (authorised/captured/reversed)
 * from Kafka, applies them idempotently into append-only ledger projections, and exposes
 * read-only query endpoints over those projections.
 */
@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    log.info("Starting Ledger service application");
    SpringApplication.run(Application.class, args);
    log.info("Ledger service application started");
  }
}
