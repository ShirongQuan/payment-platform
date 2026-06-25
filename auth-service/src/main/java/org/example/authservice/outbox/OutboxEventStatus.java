package org.example.authservice.outbox;

public enum OutboxEventStatus {
  PENDING,
  PUBLISHED,
  FAILED
}
