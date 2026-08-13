package org.example.fraud.failure;

import jakarta.validation.constraints.NotNull;

public record FailureModeRequest(@NotNull FailureMode mode) {}
