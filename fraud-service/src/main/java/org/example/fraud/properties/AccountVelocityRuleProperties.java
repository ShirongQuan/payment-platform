package org.example.fraud.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config for the "Account Velocity" rule. */
@ConfigurationProperties(prefix = "risk.rules.velocity.account")
public record AccountVelocityRuleProperties(
    boolean enabled, int threshold, int windowSeconds, int score, String reasonCode) {}
