package org.example.fraud.failure;

import jakarta.validation.constraints.NotNull;

public record FailureModeResponse(@NotNull FailureMode mode) {}
