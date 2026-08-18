package org.example.auth.fraud;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class FraudHttpClientConfig {

  @Bean
  RestClient fraudRestClient(FraudGatewayProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) properties.http().connectTimeout().toMillis());
    requestFactory.setReadTimeout((int) properties.http().readTimeout().toMillis());

    return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
  }
}


