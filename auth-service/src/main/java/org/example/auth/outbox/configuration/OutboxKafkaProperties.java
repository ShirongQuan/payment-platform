package org.example.auth.outbox.configuration;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.kafka.topic")
public record OutboxKafkaProperties(String name) {
}
