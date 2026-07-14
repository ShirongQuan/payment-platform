package org.example.ledger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
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
