package org.example.fraud.failure;

/**
 * Chaos-injection modes toggled via {@link FailureModeController} and applied by {@link
 * FailureModeService#applyMockChaosIfNeeded()} before real fraud evaluation runs.
 */
public enum FailureMode {
  /** No chaos injection; normal processing. */
  OFF,
  /** Every fraud check request fails with HTTP 500. */
  ALWAYS_500,
  /** Every fraud check request fails with HTTP 503. */
  ALWAYS_503,
  /** Every fraud check request is delayed by 2 seconds before processing continues. */
  DELAY_2S,
  /** Each request has a 50% chance of failing with HTTP 503. */
  RANDOM_50_PERCENT
}
