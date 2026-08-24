package org.example.ledger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures Swagger/OpenAPI metadata used to generate API documentation at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

  /** Builds the OpenAPI descriptor (title/version/description) shown in the generated docs. */
  @Bean
  public OpenAPI ledgerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Ledger Service API")
                .version("V1")
                .description("API documentation for the Ledger service"));
  }
}
