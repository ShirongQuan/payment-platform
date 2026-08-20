package org.example.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.WebRequest;

class ProblemDetailsTest {

  @Test
  void shouldBuildProblemDetailWithPropertiesAndInstanceUri() {
    WebRequest request = mock(WebRequest.class);
    when(request.getDescription(false)).thenReturn("uri=/fraud/check");

    ProblemDetail pd =
        ProblemDetails.from(
            HttpStatus.CONFLICT,
            "Idempotency key conflict",
            "Duplicate request with different payload",
            "IDEMPOTENCY_CONFLICT",
            request,
            "accountId",
            "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "idempotencyKey",
            "idem-key");

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(pd.getTitle()).isEqualTo("Idempotency key conflict");
    assertThat(pd.getDetail()).isEqualTo("Duplicate request with different payload");
    assertThat(pd.getProperties()).containsEntry("errorCode", "IDEMPOTENCY_CONFLICT");
    assertThat(pd.getProperties())
        .containsEntry("accountId", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        .containsEntry("idempotencyKey", "idem-key");
    assertThat(pd.getInstance().toString()).isEqualTo("/fraud/check");
  }

  @Test
  void shouldRejectOddNumberOfPropertyArguments() {
    WebRequest request = mock(WebRequest.class);
    when(request.getDescription(false)).thenReturn("uri=/accounts");

    assertThatThrownBy(
            () ->
                ProblemDetails.from(
                    HttpStatus.BAD_REQUEST,
                    "Validation error",
                    "Invalid currency",
                    "INVALID_CURRENCY",
                    request,
                    "currencyCode"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ProblemDetail property pairs must be key/value");
  }

  @Test
  void shouldRejectNonStringPropertyKey() {
    WebRequest request = mock(WebRequest.class);
    when(request.getDescription(false)).thenReturn("uri=/accounts");

    assertThatThrownBy(
            () ->
                ProblemDetails.from(
                    HttpStatus.BAD_REQUEST,
                    "Validation error",
                    "Invalid currency",
                    "INVALID_CURRENCY",
                    request,
                    123,
                    "value"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ProblemDetail property key must be a String");
  }
}


