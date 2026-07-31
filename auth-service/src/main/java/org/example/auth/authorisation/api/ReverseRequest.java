package org.example.auth.authorisation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseRequest(
    @NotBlank @Size(max = 20) String idempotencyKey, @NotBlank @Size(max = 20) String reasonCode) {}
