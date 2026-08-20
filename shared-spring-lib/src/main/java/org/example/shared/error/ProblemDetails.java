package org.example.shared.error;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.WebRequest;

/** Utility for consistent RFC 7807 ProblemDetail construction across services. */
public final class ProblemDetails {

  private ProblemDetails() {}

  public static ProblemDetail from(
      HttpStatus status, String title, String detail, String errorCode, WebRequest request) {
    return from(status, title, detail, errorCode, request, new Object[0]);
  }

  public static ProblemDetail from(
      HttpStatus status,
      String title,
      String detail,
      String errorCode,
      WebRequest request,
      Object... keyValuePairs) {
    ProblemDetail pd = base(status, title, detail, errorCode, request);
    addProperties(pd, keyValuePairs);
    return pd;
  }

  private static ProblemDetail base(
      HttpStatus status, String title, String detail, String errorCode, WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setTitle(title);
    pd.setProperty("errorCode", errorCode);
    String description = request.getDescription(false);
    if (description != null && description.startsWith("uri=")) {
      pd.setInstance(URI.create(description.substring(4)));
    }
    return pd;
  }

  private static void addProperties(ProblemDetail pd, Object... keyValuePairs) {
    if (keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException("ProblemDetail property pairs must be key/value");
    }
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      Object key = keyValuePairs[i];
      if (!(key instanceof String propertyName)) {
        throw new IllegalArgumentException("ProblemDetail property key must be a String");
      }
      pd.setProperty(propertyName, keyValuePairs[i + 1]);
    }
  }
}
