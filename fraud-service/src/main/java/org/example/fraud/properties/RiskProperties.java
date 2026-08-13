package org.example.fraud.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "risk")
public record RiskProperties(String rulesVersion, int declineThreshold) {}
