package org.example.fraud.domain;

import java.util.List;

/**
 * Final output returned by the engine.
 *
 * <p>Includes: - totalScore: sum of all triggered rule scores - triggeredRules: all rules that
 * contributed to the score
 */
public record RiskReport(int totalScore, List<RuleResult> triggeredRules) {}
