package org.example.auth.fraud;

/** Internal result returned by each rule. */
public record RuleResult(String ruleName, int score, String reason) {}
