package org.example.auth.fraud;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the outbound fraud-service HTTP client, bound from the {@code fraud} prefix.
 *
 * @param baseUrl base URL of the fraud service (must start with http:// or https://)
 * @param http connection/read timeout settings for the HTTP client
 */
@ConfigurationProperties(prefix = "fraud")
@Validated
public record FraudGatewayProperties(
    @NotBlank(message = "fraud.base-url must not be blank")
        @Pattern(
            regexp = "^https?://.+",
            message = "fraud.base-url must start with http:// or https://")
        String baseUrl,
    @Valid @NotNull(message = "fraud.http is required") Http http) {

  /** HTTP client timeout settings. */
  public record Http(
      @NotNull(message = "fraud.http.connect-timeout is required") Duration connectTimeout,
      @NotNull(message = "fraud.http.read-timeout is required") Duration readTimeout) {
    @AssertTrue(message = "fraud.http.connect-timeout must be greater than 0")
    public boolean isConnectTimeoutPositive() {
      return connectTimeout == null || (!connectTimeout.isNegative() && !connectTimeout.isZero());
    }

    @AssertTrue(message = "fraud.http.read-timeout must be greater than 0")
    public boolean isReadTimeoutPositive() {
      return readTimeout == null || (!readTimeout.isNegative() && !readTimeout.isZero());
    }
  }
}




