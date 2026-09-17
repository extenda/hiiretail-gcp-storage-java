package com.retailsvc.gcp.storage;

/**
 * Downloaded content could not be verified against its stored crc32c. Retrying the download may
 * succeed.
 */
public class ChecksumMismatchException extends GcsClientException {

  public ChecksumMismatchException(String message) {
    super(message, 0, true, null);
  }
}
