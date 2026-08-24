package org.example.fraud.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config for the "Amount Deviation" rule (flags spend that deviates from an account's baseline). */
@ConfigurationProperties(prefix = "risk.rules.amount-deviation")
public record AmountDeviationRuleProperties(
    boolean enabled,
    int amountMultiplier,
    int windowDays,
    long amountReference,
    int minSamples,
    int cacheTtlMinutes,
    int score,
    String reasonCode) {}
