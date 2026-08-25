package org.example.fraud.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the account-lock signal returned alongside a fraud decision.
 *
 * <p>A lock is recommended to the caller (auth-service) when either the account has been declined
 * more than {@code repeatedDeclineThreshold} times within {@code repeatedDeclineWindowSeconds}, or
 * the current request's risk score meets/exceeds {@code highRiskScoreThreshold}. The lock signal is
 * only ever considered on a DECLINE decision, never on APPROVE.
 */
@ConfigurationProperties(prefix = "risk.lock")
public record AccountLockRuleProperties(
    boolean enabled,
    int repeatedDeclineThreshold,
    int repeatedDeclineWindowSeconds,
    int highRiskScoreThreshold,
    String repeatedDeclineReasonCode,
    String highRiskScoreReasonCode) {}

