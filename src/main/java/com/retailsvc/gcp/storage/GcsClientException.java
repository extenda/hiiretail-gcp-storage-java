package com.retailsvc.gcp.storage;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;

import com.google.auth.Retryable;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class GcsClientException extends RuntimeException {

  private static final int TOO_MANY_REQUESTS = 429;

  private final int status;
  private final boolean retryable;

  /**
   * @param status the HTTP status, or 0 when no response was received
   * @param retryable whether retrying the same call may succeed
   */
  public GcsClientException(String message, int status, boolean retryable, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.retryable = retryable;
  }

  static GcsClientException ofStatus(int status, String message) {
    return new GcsClientException(
        message,
        status,
        status == HTTP_CLIENT_TIMEOUT
            || status == TOO_MANY_REQUESTS
            || status >= HTTP_INTERNAL_ERROR,
        null);
  }

  /**
   * No response was received. Retryable when caused by I/O or a timeout, unless the cause says
   * otherwise, as google-auth errors do.
   */
  static GcsClientException noResponse(String message, Throwable cause) {
    var retryable =
        cause instanceof Retryable r
            ? r.isRetryable()
            : cause instanceof IOException || cause instanceof TimeoutException;
    return new GcsClientException(message, 0, retryable, cause);
  }

  /** The HTTP status, or 0 when no response was received. */
  public int status() {
    return status;
  }

  /** Whether retrying the same call may succeed. */
  public boolean retryable() {
    return retryable;
  }
}
