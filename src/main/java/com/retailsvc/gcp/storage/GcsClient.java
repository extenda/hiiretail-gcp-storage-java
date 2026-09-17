package com.retailsvc.gcp.storage;

import java.util.Map;
import java.util.Optional;

/**
 * File operations on Google Cloud Storage buckets. Content is checksummed (crc32c) on upload and
 * verified on download.
 */
public interface GcsClient extends AutoCloseable {

  /**
   * Creates a file.
   *
   * @param contentType the content type, or {@code null} for {@code application/octet-stream}
   * @throws AlreadyExistsException if the file exists
   */
  void save(String bucket, String name, byte[] content, String contentType);

  /**
   * Creates or replaces a file.
   *
   * @param contentType the content type, or {@code null} for {@code application/octet-stream}
   */
  void overwrite(String bucket, String name, byte[] content, String contentType);

  /**
   * {@link #save}s the files in parallel, without stopping at failures.
   *
   * @return the failures by file name, in the files' iteration order; empty when every file was
   *     saved
   * @throws GcsClientException if the client is closed or the calling thread is interrupted
   */
  Map<String, GcsClientException> saveAll(
      String bucket, Map<String, byte[]> files, String contentType);

  /**
   * Reads a file.
   *
   * @return the verified content, or empty if the file doesn't exist
   * @throws ChecksumMismatchException if the content doesn't match its stored crc32c
   */
  Optional<byte[]> load(String bucket, String name);

  /**
   * Deletes a file.
   *
   * @return {@code false} if the file didn't exist
   */
  boolean delete(String bucket, String name);

  /**
   * Renames a file within a bucket, atomically. Against an emulator, which has no atomic move, it
   * copies and deletes like {@link #moveAcrossBuckets}.
   *
   * @throws AlreadyExistsException if the target exists
   * @throws GcsClientException with status 404 if the source doesn't exist
   */
  void move(String bucket, String from, String to);

  /**
   * Moves a file to another bucket by copying it and then deleting the source. Not atomic: if the
   * delete fails, both files remain.
   *
   * @throws AlreadyExistsException if the target exists
   * @throws GcsClientException with status 404 if the source doesn't exist
   * @throws IllegalArgumentException if both buckets are the same; use {@link #move} instead
   */
  void moveAcrossBuckets(String fromBucket, String from, String toBucket, String to);

  @Override
  void close();
}
