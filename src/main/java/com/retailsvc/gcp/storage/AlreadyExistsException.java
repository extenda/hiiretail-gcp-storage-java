package com.retailsvc.gcp.storage;

import static java.net.HttpURLConnection.HTTP_PRECON_FAILED;

/** The target file already exists. Not retryable. */
public class AlreadyExistsException extends GcsClientException {

  public AlreadyExistsException(String message) {
    super(message, HTTP_PRECON_FAILED, false, null);
  }
}
