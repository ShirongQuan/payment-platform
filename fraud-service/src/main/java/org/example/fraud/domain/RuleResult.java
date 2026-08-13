package org.example.fraud.domain;

/**
 * Internal result returned by each rule.
 *
 * <p>Factory methods make rule implementations cleaner and reduce mistakes.
 */
public record RuleResult(String ruleName, int score, String reason) {}
