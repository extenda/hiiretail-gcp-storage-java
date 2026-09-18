package com.retailsvc.gcp.storage;

/**
 * Downloaded content could not be verified against its stored crc32c. Retrying the download may
 * succeed.
 */
public class ChecksumMismatchException extends GcsClientException {

  /**
   * @param status the HTTP status of the response whose content failed verification
   */
  public ChecksumMismatchException(String message, int status) {
    super(message, status, true, null);
  }
}
