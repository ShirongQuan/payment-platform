package org.example.fraud.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config for the "IP velocity" rule. */
@ConfigurationProperties(prefix = "risk.rules.velocity.ip")
public record IpVelocityRuleProperties(
    boolean enabled, int threshold, int windowSeconds, int score, String reasonCode) {}
