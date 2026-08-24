package org.example.auth.authorisation.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.auth.authorisation.domain.AuthorisationEventReason;

/** Request body to reverse (release) a previously authorised amount. */
public record ReverseRequest(
    @NotBlank @Size(max = 20) String idempotencyKey,
    @NotNull @Schema(description = "Reason code for reversal")
        AuthorisationEventReason reasonCode) {}
