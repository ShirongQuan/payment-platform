package org.example.auth.authorisation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CaptureRequest(@NotBlank @Size(max = 20) String idempotencyKey) {}
