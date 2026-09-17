package com.retailsvc.gcp.storage;

/** The target file already exists. Not retryable. */
public class AlreadyExistsException extends GcsClientException {

  public AlreadyExistsException(String message) {
    super(message, 412, false, null);
  }
}
