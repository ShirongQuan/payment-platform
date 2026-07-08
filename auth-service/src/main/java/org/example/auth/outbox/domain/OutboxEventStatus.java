package org.example.auth.outbox.domain;

public enum OutboxEventStatus {
  NEW,
  PUBLISHING,
  PUBLISHED,
  FAILED
}
