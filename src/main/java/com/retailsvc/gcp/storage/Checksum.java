package com.retailsvc.gcp.storage;

import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Objects;

public class Checksum {

  private Checksum() {}

  /**
   * Hash and base64 encode byte contents.
   *
   * @param data the contents to make a checksum (crc32c) of.
   * @return the calculated checksum
   */
  public static String crc32cBase64(byte[] data) {
    Objects.requireNonNull(data);

    var fn = Hashing.crc32c();

    var hashCode = fn.hashBytes(data).asBytes();
    /* LittleEndian buffer to hold bytes */
    ByteBuffer littleEndian = ByteBuffer.wrap(hashCode).order(ByteOrder.LITTLE_ENDIAN);
    /* Extract LittleEndian into BigEndian to reverse byte order */
    ByteBuffer bigEndian = ByteBuffer.allocate(Integer.BYTES).putInt(littleEndian.getInt());
    /* base64 encode bigEndian bytes */
    return Base64.getEncoder().encodeToString(bigEndian.array());
  }
}
