package org.example.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Helpers for canonicalizing request data and computing deterministic idempotency hashes. */
public final class RequestHashing {

  private RequestHashing() {}

  public static String canonicalJoin(String... values) {
    if (values == null) {
      throw new IllegalArgumentException("Values must not be null");
    }
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        builder.append('|');
      }
      String value = values[i];
      if (value == null) {
        // Use -1 to keep null distinct from an empty string.
        builder.append("-1:");
      } else {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        builder.append(length).append(':').append(value);
      }
    }
    return builder.toString();
  }

  public static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to compute SHA-256 hash", e);
    }
  }
}
