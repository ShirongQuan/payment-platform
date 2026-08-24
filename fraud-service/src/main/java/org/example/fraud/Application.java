package org.example.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Fraud-service entry point: evaluates payment fraud risk via a pluggable set of {@link
 * org.example.fraud.component.RiskRule}s and exposes results to auth-service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
