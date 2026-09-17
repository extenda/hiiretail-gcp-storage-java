package com.retailsvc.gcp.storage;

import com.google.auth.Retryable;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class GcsClientException extends RuntimeException {

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
        message, status, status == 408 || status == 429 || status >= 500, null);
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
