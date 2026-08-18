package org.example.auth.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FailOpenPolicyTest {

  @Test
  void shouldAllowWhenEnabledTrustedAndAmountWithinCap() {
    UUID trustedId = UUID.randomUUID();
    FailOpenPolicy policy =
        new FailOpenPolicy(properties(true, new BigDecimal("5.00"), List.of(trustedId.toString())));

    boolean allowed = policy.allow(trustedId, new BigDecimal("4.99"));

    assertThat(allowed).isTrue();
  }

  @Test
  void shouldDenyWhenPolicyDisabled() {
    UUID trustedId = UUID.randomUUID();
    FailOpenPolicy policy =
        new FailOpenPolicy(properties(false, new BigDecimal("5.00"), List.of(trustedId.toString())));

    boolean allowed = policy.allow(trustedId, new BigDecimal("1.00"));

    assertThat(allowed).isFalse();
  }

  @Test
  void shouldDenyWhenAccountIsNotTrusted() {
    FailOpenPolicy policy =
        new FailOpenPolicy(
            properties(true, new BigDecimal("5.00"), List.of(UUID.randomUUID().toString())));

    boolean allowed = policy.allow(UUID.randomUUID(), new BigDecimal("1.00"));

    assertThat(allowed).isFalse();
  }

  @Test
  void shouldDenyWhenAmountExceedsCap() {
    UUID trustedId = UUID.randomUUID();
    FailOpenPolicy policy =
        new FailOpenPolicy(properties(true, new BigDecimal("5.00"), List.of(trustedId.toString())));

    boolean allowed = policy.allow(trustedId, new BigDecimal("5.01"));

    assertThat(allowed).isFalse();
  }

  private static FailOpenProperties properties(
      boolean enabled, BigDecimal maxAmount, List<String> trustedAccountIds) {
    return new FailOpenProperties(enabled, maxAmount, trustedAccountIds);
  }
}

