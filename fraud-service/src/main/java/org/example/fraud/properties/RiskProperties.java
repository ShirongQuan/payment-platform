package org.example.fraud.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Global risk-scoring configuration: rules version tag and the decline threshold. */
@ConfigurationProperties(prefix = "risk")
public record RiskProperties(String rulesVersion, int declineThreshold) {}
