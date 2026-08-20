package org.example.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequestHashingTest {

  @Test
  void shouldUseLengthPrefixedEncodingToAvoidDelimiterAmbiguity() {
    String first = RequestHashing.canonicalJoin("ab|c", "d");
    String second = RequestHashing.canonicalJoin("ab", "c|d");

    assertThat(first).isNotEqualTo(second);
    assertThat(first).isEqualTo("4:ab|c|1:d");
    assertThat(second).isEqualTo("2:ab|3:c|d");
  }

  @Test
  void shouldDistinguishNullFromEmpty() {
    String nullValue = RequestHashing.canonicalJoin((String) null);
    String emptyValue = RequestHashing.canonicalJoin("");

    assertThat(nullValue).isEqualTo("-1:");
    assertThat(emptyValue).isEqualTo("0:");
    assertThat(nullValue).isNotEqualTo(emptyValue);
  }

  @Test
  void shouldRejectNullArrayInput() {
    assertThatThrownBy(() -> RequestHashing.canonicalJoin((String[]) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Values must not be null");
  }
}

