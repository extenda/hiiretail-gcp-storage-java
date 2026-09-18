package com.retailsvc.gcp.storage;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.CRC32C;

public final class Checksum {

  private Checksum() {}

  /**
   * Hash and base64 encode byte contents, in the big-endian form GCS uses for crc32c.
   *
   * @param data the contents to make a checksum (crc32c) of.
   * @return the calculated checksum
   */
  public static String crc32cBase64(byte[] data) {
    Objects.requireNonNull(data);

    var crc32c = new CRC32C();
    crc32c.update(data);
    var bigEndian = ByteBuffer.allocate(Integer.BYTES).putInt((int) crc32c.getValue());
    return Base64.getEncoder().encodeToString(bigEndian.array());
  }
}
