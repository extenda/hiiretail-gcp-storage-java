package com.retailsvc.gcp.storage;

import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auth.Credentials;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** {@link GcsClient} over the GCS JSON API. */
final class GcsClientImpl implements GcsClient {

  private static final String STORAGE = "/storage/v1";
  private static final String IF_GENERATION_MATCH = "ifGenerationMatch";
  private static final String CRC32C = "crc32c=";

  private final HttpClient http;
  private final Credentials credentials;
  private final String endpoint;
  private final Duration timeout;
  private final boolean atomicMove;
  private final Semaphore uploads;
  private volatile boolean closed;

  /**
   * @param atomicMove whether the server supports the atomic {@code moveTo}; emulators don't
   * @param maxConcurrency the most uploads {@link #saveAll} runs at once, across calls
   */
  GcsClientImpl(
      HttpClient http,
      Credentials credentials,
      String endpoint,
      Duration timeout,
      boolean atomicMove,
      int maxConcurrency) {
    this.http = http;
    this.credentials = credentials;
    this.endpoint = endpoint;
    this.timeout = timeout;
    this.atomicMove = atomicMove;
    this.uploads = new Semaphore(maxConcurrency);
  }

  @Override
  public void save(String bucket, String name, byte[] content, String contentType) {
    upload(bucket, name, content, contentType, true);
  }

  @Override
  public void overwrite(String bucket, String name, byte[] content, String contentType) {
    upload(bucket, name, content, contentType, false);
  }

  @Override
  public Map<String, GcsClientException> saveAll(
      String bucket, Map<String, byte[]> files, String contentType) {
    ensureOpen();
    var names = new ArrayList<String>(files.size());
    var saves = new ArrayList<Callable<Optional<GcsClientException>>>(files.size());
    files.forEach(
        (name, content) -> {
          names.add(name);
          saves.add(() -> saveReporting(bucket, name, content, contentType));
        });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = executor.invokeAll(saves);
      var failures = new LinkedHashMap<String, GcsClientException>();
      for (int i = 0; i < results.size(); i++) {
        var name = names.get(i);
        failure(results.get(i)).ifPresent(e -> failures.put(name, e));
      }
      return failures;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw GcsClientException.noResponse("saveAll interrupted", e);
    }
  }

  private Optional<GcsClientException> saveReporting(
      String bucket, String name, byte[] content, String contentType) throws InterruptedException {
    uploads.acquire();
    try {
      save(bucket, name, content, contentType);
      return Optional.empty();
    } catch (GcsClientException e) {
      return Optional.of(e);
    } finally {
      uploads.release();
    }
  }

  /**
   * A save's reported failure; anything else it threw, such as a programming error, is rethrown.
   */
  private static Optional<GcsClientException> failure(Future<Optional<GcsClientException>> save) {
    if (save.state() == Future.State.FAILED && save.exceptionNow() instanceof RuntimeException e) {
      throw e;
    }
    return save.resultNow();
  }

  @Override
  public Optional<byte[]> load(String bucket, String name) {
    var uri = uri(STORAGE + ref(bucket, name), "alt", "media");
    var response = send(request(uri).GET(), BodyHandlers.ofByteArray());
    if (response.statusCode() == HTTP_NOT_FOUND) {
      return Optional.empty();
    }
    var content = ok(response).body();
    var stored = storedCrc32c(response);
    var actual = Checksum.crc32cBase64(content);
    if (!actual.equals(stored)) {
      throw new ChecksumMismatchException(
          "crc32c mismatch for gs://%s/%s: stored %s, downloaded %s"
              .formatted(bucket, name, stored, actual),
          response.statusCode());
    }
    return Optional.of(content);
  }

  @Override
  public boolean delete(String bucket, String name) {
    return delete(uri(STORAGE + ref(bucket, name)));
  }

  @Override
  public void move(String bucket, String from, String to) {
    if (!atomicMove) {
      copyThenDelete(bucket, from, bucket, to);
      return;
    }
    var uri = uri(STORAGE + ref(bucket, from) + "/moveTo/o/" + encode(to), IF_GENERATION_MATCH, 0);
    var response = post(uri);
    if (response.statusCode() == HTTP_NOT_FOUND) {
      throw notFound(bucket, from);
    }
    created(response, bucket, to);
  }

  @Override
  public void moveAcrossBuckets(String fromBucket, String from, String toBucket, String to) {
    if (fromBucket.equals(toBucket)) {
      throw new IllegalArgumentException(
          "Both files are in bucket %s; use move(...)".formatted(fromBucket));
    }
    copyThenDelete(fromBucket, from, toBucket, to);
  }

  private void copyThenDelete(String fromBucket, String from, String toBucket, String to) {
    var statUri = uri(STORAGE + ref(fromBucket, from));
    var stat = send(request(statUri).GET(), BodyHandlers.ofString());
    if (stat.statusCode() == HTTP_NOT_FOUND) {
      throw notFound(fromBucket, from);
    }
    var generation = GcsJson.generation(ok(stat).body());
    GcsJson.Rewrite rewrite = null;
    do {
      var uri =
          uri(
              STORAGE + ref(fromBucket, from) + "/rewriteTo" + ref(toBucket, to),
              "sourceGeneration",
              generation,
              IF_GENERATION_MATCH,
              0,
              "rewriteToken",
              rewrite == null ? null : rewrite.rewriteToken());
      rewrite = GcsJson.rewrite(created(post(uri), toBucket, to).body());
    } while (!rewrite.done());
    // A generation-less id with a generation guard: a changed source fails with 412, not 404.
    var deleteUri = uri(STORAGE + ref(fromBucket, from), IF_GENERATION_MATCH, generation);
    if (!delete(deleteUri)) {
      throw GcsClientException.ofStatus(
          HTTP_NOT_FOUND,
          "Source gs://%s/%s vanished before delete; copied to gs://%s/%s"
              .formatted(fromBucket, from, toBucket, to));
    }
  }

  @Override
  public void close() {
    closed = true;
    http.close();
  }

  private void upload(
      String bucket, String name, byte[] content, String contentType, boolean createOnly) {
    var metadata = GcsJson.uploadMetadata(name, contentType, Checksum.crc32cBase64(content));
    var boundary = UUID.randomUUID().toString();
    var head =
        """
        --%1$s\r
        Content-Type: application/json; charset=UTF-8\r
        \r
        %2$s\r
        --%1$s\r
        Content-Type: %3$s\r
        \r
        """
            .formatted(
                boundary,
                metadata,
                Objects.requireNonNullElse(contentType, "application/octet-stream"));
    var tail = "\r\n--" + boundary + "--\r\n";
    var uri =
        uri(
            "/upload" + STORAGE + "/b/" + encode(bucket) + "/o",
            "uploadType",
            "multipart",
            IF_GENERATION_MATCH,
            createOnly ? 0 : null);
    var request =
        request(uri)
            .header("Content-Type", "multipart/related; boundary=" + boundary)
            .POST(
                BodyPublishers.ofByteArrays(
                    List.of(head.getBytes(UTF_8), content, tail.getBytes(UTF_8))));
    created(send(request, BodyHandlers.ofString()), bucket, name);
  }

  private static GcsClientException notFound(String bucket, String name) {
    return GcsClientException.ofStatus(
        HTTP_NOT_FOUND, "gs://%s/%s not found".formatted(bucket, name));
  }

  private boolean delete(URI uri) {
    var response = send(request(uri).DELETE(), BodyHandlers.ofString());
    if (response.statusCode() == HTTP_NOT_FOUND) {
      return false;
    }
    ok(response);
    return true;
  }

  private HttpResponse<String> post(URI uri) {
    return send(request(uri).POST(BodyPublishers.noBody()), BodyHandlers.ofString());
  }

  /** The crc32c from {@code x-goog-hash}, sent as one comma-separated header or several. */
  private static String storedCrc32c(HttpResponse<?> response) {
    return response.headers().allValues("x-goog-hash").stream()
        .flatMap(value -> Arrays.stream(value.split(",")))
        .map(String::strip)
        .filter(hash -> hash.startsWith(CRC32C))
        .map(hash -> hash.substring(CRC32C.length()))
        .findFirst()
        .orElse(null);
  }

  /** The {@code /b/{bucket}/o/{name}} part of a JSON API path. */
  private static String ref(String bucket, String name) {
    return "/b/" + encode(bucket) + "/o/" + encode(name);
  }

  /** Builds a URI from a path and key/value query pairs, skipping {@code null} values. */
  private URI uri(String path, Object... query) {
    var joiner = new StringJoiner("&", "?", "").setEmptyValue("");
    for (int i = 0; i < query.length; i += 2) {
      if (query[i + 1] != null) {
        joiner.add(query[i] + "=" + encode(String.valueOf(query[i + 1])));
      }
    }
    return URI.create(endpoint + path + joiner);
  }

  /** Percent-encodes a path segment or query value. */
  private static String encode(String value) {
    return URLEncoder.encode(value, UTF_8).replace("+", "%20");
  }

  private void ensureOpen() {
    if (closed) {
      throw GcsClientException.noResponse("Client is closed", null);
    }
  }

  private HttpRequest.Builder request(URI uri) {
    ensureOpen();
    var builder = HttpRequest.newBuilder(uri);
    try {
      credentials
          .getRequestMetadata(uri)
          .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
    } catch (IOException e) {
      throw GcsClientException.noResponse("Failed to obtain credentials", e);
    }
    return builder;
  }

  /**
   * Sends with one deadline for the whole exchange, as {@code HttpRequest#timeout} does not cover
   * the response body.
   */
  private <T> HttpResponse<T> send(HttpRequest.Builder request, BodyHandler<T> handler) {
    var built = request.build();
    var call = "%s %s".formatted(built.method(), built.uri());
    var response = http.sendAsync(built, handler);
    try {
      return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw GcsClientException.noResponse(call + " failed", e.getCause());
    } catch (TimeoutException e) {
      response.cancel(true);
      throw GcsClientException.noResponse(call + " timed out after " + timeout, e);
    } catch (InterruptedException e) {
      response.cancel(true);
      Thread.currentThread().interrupt();
      throw GcsClientException.noResponse(call + " interrupted", e);
    }
  }

  /** Like {@link #ok}, but a failed create-only precondition means the target exists. */
  private static <T> HttpResponse<T> created(HttpResponse<T> response, String bucket, String name) {
    if (response.statusCode() == HTTP_PRECON_FAILED) {
      throw new AlreadyExistsException("gs://%s/%s already exists".formatted(bucket, name));
    }
    return ok(response);
  }

  private static <T> HttpResponse<T> ok(HttpResponse<T> response) {
    if (response.statusCode() >= HTTP_MULT_CHOICE) {
      var body =
          response.body() instanceof byte[] bytes
              ? new String(bytes, UTF_8)
              : String.valueOf(response.body());
      throw GcsClientException.ofStatus(
          response.statusCode(),
          "%s %s returned %d: %s"
              .formatted(response.request().method(), response.uri(), response.statusCode(), body));
    }
    return response;
  }
}
