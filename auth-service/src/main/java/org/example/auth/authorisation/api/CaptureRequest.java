package org.example.auth.authorisation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body to capture (settle) a previously authorised amount. */
public record CaptureRequest(@NotBlank @Size(max = 20) String idempotencyKey) {}
