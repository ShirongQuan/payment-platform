package org.example.fraud.failure;

public enum FailureMode {
  OFF,
  ALWAYS_500,
  ALWAYS_503,
  DELAY_2S,
  RANDOM_50_PERCENT
}
