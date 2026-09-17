package com.retailsvc.gcp.storage;

import static com.retailsvc.gcp.storage.StubServer.respond;
import static com.retailsvc.gcp.storage.StubServer.stallBody;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;

import com.google.auth.Retryable;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.retailsvc.gcp.storage.StubServer.Recorded;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GcsClientImplTest {

  private static final String IF_GENERATION_MATCH = "ifGenerationMatch";
  private static final String HASH = "x-goog-hash";
  private static final String HELLO_HASH = "crc32c=mnG7TA==,md5=XUFAKrxLKna5cZ2REBfFkg==";
  private static final String SOURCE_JSON =
      "{\"bucket\":\"b\",\"name\":\"o\",\"generation\":\"7\"}";
  private static final String REWRITE_DONE = "{\"done\":true,\"resource\":{\"generation\":\"8\"}}";
  private static final byte[] HELLO = "hello".getBytes(UTF_8);
  private static final GoogleCredentials TOKEN =
      GoogleCredentials.create(new AccessToken("t", null));

  private final StubServer server = new StubServer();
  private final GcsClient client = client(Duration.ofSeconds(5));

  @AfterEach
  void tearDown() {
    client.close();
    server.close();
  }

  private GcsClient client(Duration timeout) {
    return client(TOKEN, timeout, true, 16);
  }

  private GcsClient client(
      GoogleCredentials credentials, Duration timeout, boolean atomicMove, int maxConcurrency) {
    return new GcsClientImpl(
        HttpClient.newHttpClient(), credentials, server.url(), timeout, atomicMove, maxConcurrency);
  }

  private static Map<String, String> query(Recorded request) {
    var raw = request.uri().getRawQuery();
    return raw == null
        ? Map.of()
        : Arrays.stream(raw.split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));
  }

  private Recorded onlyRequest() {
    assertThat(server.requests()).hasSize(1);
    return server.requests().getFirst();
  }

  @Test
  void saveUploadsCreateOnlyWithChecksumAndCredentials() {
    server.enqueue(respond(200, "{}"));

    client.save("b", "dir/o", HELLO, "text/plain");

    var request = onlyRequest();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.uri().getRawPath()).isEqualTo("/upload/storage/v1/b/b/o");
    assertThat(query(request))
        .containsOnly(entry("uploadType", "multipart"), entry(IF_GENERATION_MATCH, "0"));
    assertThat(request.headers().get("Authorization")).containsExactly("Bearer t");
    assertThat(request.headers().getFirst("Content-Type"))
        .startsWith("multipart/related; boundary=");
    assertThat(new String(request.body(), UTF_8))
        .contains("{\"name\":\"dir/o\",\"contentType\":\"text/plain\",\"crc32c\":\"mnG7TA==\"}")
        .contains("Content-Type: text/plain\r\n\r\nhello\r\n");
  }

  @Test
  void saveOfExistingFileThrowsAlreadyExists() {
    server.enqueue(respond(412, "{}"));

    assertThatExceptionOfType(AlreadyExistsException.class)
        .isThrownBy(() -> client.save("b", "o", HELLO, "text/plain"))
        .satisfies(e -> assertThat(e.status()).isEqualTo(412))
        .satisfies(e -> assertThat(e.retryable()).isFalse());
  }

  @Test
  void overwriteUploadsWithoutPrecondition() {
    server.enqueue(respond(200, "{}"));

    client.overwrite("b", "o", HELLO, null);

    var request = onlyRequest();
    assertThat(query(request)).containsOnly(entry("uploadType", "multipart"));
    assertThat(new String(request.body(), UTF_8))
        .contains("{\"name\":\"o\",\"crc32c\":\"mnG7TA==\"}")
        .contains("Content-Type: application/octet-stream\r\n\r\nhello\r\n");
  }

  @ParameterizedTest
  @CsvSource({"400, false", "429, true", "503, true"})
  void failedResponseCarriesStatus(int status, boolean retryable) {
    server.enqueue(respond(status, "{}"));

    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.overwrite("b", "o", HELLO, null))
        .satisfies(e -> assertThat(e.status()).isEqualTo(status))
        .satisfies(e -> assertThat(e.retryable()).isEqualTo(retryable));
  }

  @Test
  void saveAllSavesEveryFileCreateOnlyAndReportsFailuresInIterationOrder() {
    var files = new LinkedHashMap<String, byte[]>();
    for (var name : new String[] {"a", "fail1", "b", "fail2", "c"}) {
      files.put(name, HELLO);
      server.enqueue(
          (request, exchange) ->
              (new String(request.body(), UTF_8).contains("\"name\":\"fail")
                      ? respond(503, "{}")
                      : respond(200, "{}"))
                  .respond(request, exchange));
    }

    var failures = client.saveAll("b", files, null);

    assertThat(server.requests())
        .hasSize(5)
        .allSatisfy(request -> assertThat(query(request)).containsEntry(IF_GENERATION_MATCH, "0"));
    assertThat(failures.keySet()).containsExactly("fail1", "fail2");
    assertThat(failures.values()).allSatisfy(e -> assertThat(e.retryable()).isTrue());
  }

  @Test
  void saveAllRunsAtMostMaxConcurrencyUploadsAtOnce() {
    var inFlight = new AtomicInteger();
    var maxInFlight = new AtomicInteger();
    var files = new LinkedHashMap<String, byte[]>();
    for (int i = 0; i < 6; i++) {
      files.put("o" + i, HELLO);
      server.enqueue(
          (request, exchange) -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            respond(200, "{}").respond(request, exchange);
          });
    }

    try (var limited = client(TOKEN, Duration.ofSeconds(5), true, 2)) {
      assertThat(limited.saveAll("b", files, null)).isEmpty();
    }

    assertThat(maxInFlight).hasValue(2);
  }

  @Test
  void saveAllThrowsProgrammingErrors() {
    var files = new LinkedHashMap<String, byte[]>();
    files.put("o", null);

    assertThatNullPointerException().isThrownBy(() -> client.saveAll("b", files, null));
  }

  static Stream<Arguments> hashHeaders() {
    return Stream.of(
        Arguments.of((Object) new String[] {HASH, HELLO_HASH}),
        Arguments.of(
            (Object) new String[] {HASH, "crc32c=mnG7TA==", HASH, "md5=XUFAKrxLKna5cZ2REBfFkg=="}));
  }

  @ParameterizedTest
  @MethodSource("hashHeaders")
  void loadVerifiesContentAgainstItsHash(String[] headers) {
    server.enqueue(respond(200, "hello", headers));

    assertThat(client.load("b", "dir/o")).contains(HELLO);

    var request = onlyRequest();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.uri().getRawPath()).isEqualTo("/storage/v1/b/b/o/dir%2Fo");
    assertThat(query(request)).containsOnly(entry("alt", "media"));
  }

  @Test
  void loadOfMissingFileIsEmpty() {
    server.enqueue(respond(404, "{}"));

    assertThat(client.load("b", "o")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"crc32c=AAAAAA==", "md5=XUFAKrxLKna5cZ2REBfFkg=="})
  void loadRejectsContentThatCannotBeVerified(String hash) {
    server.enqueue(respond(200, "hello", HASH, hash));

    assertThatExceptionOfType(ChecksumMismatchException.class)
        .isThrownBy(() -> client.load("b", "o"))
        .satisfies(e -> assertThat(e.status()).isEqualTo(200))
        .satisfies(e -> assertThat(e.retryable()).isTrue());
  }

  @Test
  @Timeout(5)
  void stalledDownloadTimesOut() {
    server.enqueue(stallBody());
    try (var impatient = client(Duration.ofMillis(200))) {
      assertThatExceptionOfType(GcsClientException.class)
          .isThrownBy(() -> impatient.load("b", "o"))
          .withCauseInstanceOf(TimeoutException.class)
          .satisfies(e -> assertThat(e.status()).isZero())
          .satisfies(e -> assertThat(e.retryable()).isTrue());
    }
  }

  @Test
  void deleteReportsWhetherFileExisted() {
    server.enqueue(respond(204, ""), respond(404, "{}"));

    assertThat(client.delete("b", "a/o")).isTrue();
    assertThat(client.delete("b", "a/o")).isFalse();

    assertThat(server.requests())
        .allSatisfy(
            request -> {
              assertThat(request.method()).isEqualTo("DELETE");
              assertThat(request.uri().getRawPath()).isEqualTo("/storage/v1/b/b/o/a%2Fo");
              assertThat(query(request)).isEmpty();
            });
  }

  @Test
  void moveRenamesAtomicallyAndCreateOnly() {
    server.enqueue(respond(200, "{}"));

    client.move("b", "o", "p/q");

    var request = onlyRequest();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.uri().getRawPath()).isEqualTo("/storage/v1/b/b/o/o/moveTo/o/p%2Fq");
    assertThat(query(request)).containsOnly(entry(IF_GENERATION_MATCH, "0"));
  }

  @Test
  void moveOntoExistingFileThrowsAlreadyExists() {
    server.enqueue(respond(412, "{}"));

    assertThatExceptionOfType(AlreadyExistsException.class)
        .isThrownBy(() -> client.move("b", "o", "p"));
  }

  @Test
  void moveOfMissingFileFails() {
    server.enqueue(respond(404, "{}"));

    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.move("b", "o", "p"))
        .withMessage("gs://b/o not found")
        .satisfies(e -> assertThat(e.status()).isEqualTo(404));
  }

  @Test
  void moveWithoutAtomicMoveCopiesThenDeletesWithinTheBucket() {
    server.enqueue(respond(200, SOURCE_JSON), respond(200, REWRITE_DONE), respond(204, ""));

    try (var emulated = client(TOKEN, Duration.ofSeconds(5), false, 16)) {
      emulated.move("b", "o", "p");
    }

    assertThat(server.requests())
        .extracting(Recorded::method)
        .containsExactly("GET", "POST", "DELETE");
    assertThat(server.requests().get(1).uri().getRawPath())
        .isEqualTo("/storage/v1/b/b/o/o/rewriteTo/b/b/o/p");
  }

  @Test
  void moveAcrossBucketsCopiesCreateOnlyUntilDoneThenDeletesTheCopiedSource() {
    server.enqueue(
        respond(200, SOURCE_JSON),
        respond(200, "{\"done\":false,\"rewriteToken\":\"tok\"}"),
        respond(200, REWRITE_DONE),
        respond(204, ""));

    client.moveAcrossBuckets("b", "o", "c", "p/q");

    var requests = server.requests();
    assertThat(requests)
        .extracting(Recorded::method)
        .containsExactly("GET", "POST", "POST", "DELETE");
    assertThat(requests.get(0).uri().getRawPath()).isEqualTo("/storage/v1/b/b/o/o");
    assertThat(requests.get(1).uri().getRawPath())
        .isEqualTo("/storage/v1/b/b/o/o/rewriteTo/b/c/o/p%2Fq");
    assertThat(query(requests.get(1)))
        .containsOnly(entry("sourceGeneration", "7"), entry(IF_GENERATION_MATCH, "0"));
    assertThat(query(requests.get(2)))
        .containsOnly(
            entry("sourceGeneration", "7"),
            entry(IF_GENERATION_MATCH, "0"),
            entry("rewriteToken", "tok"));
    assertThat(requests.get(3).uri().getRawPath()).isEqualTo("/storage/v1/b/b/o/o");
    assertThat(query(requests.get(3))).containsOnly(entry(IF_GENERATION_MATCH, "7"));
  }

  @Test
  void moveAcrossBucketsOntoExistingFileThrowsAlreadyExistsAndKeepsSource() {
    server.enqueue(respond(200, SOURCE_JSON), respond(412, "{}"));

    assertThatExceptionOfType(AlreadyExistsException.class)
        .isThrownBy(() -> client.moveAcrossBuckets("b", "o", "c", "p"));
    assertThat(server.requests()).extracting(Recorded::method).containsExactly("GET", "POST");
  }

  @Test
  void moveAcrossBucketsOfMissingSourceFails() {
    server.enqueue(respond(404, "{}"));

    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.moveAcrossBuckets("b", "o", "c", "p"))
        .satisfies(e -> assertThat(e.status()).isEqualTo(404));
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void moveAcrossBucketsRejectsTheSameBucket() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.moveAcrossBuckets("b", "o", "b", "p"))
        .withMessageContaining("move(");
    assertThat(server.requests()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 412})
  void moveAcrossBucketsFailsWhenSourceChangesBeforeDelete(int status) {
    server.enqueue(respond(200, SOURCE_JSON), respond(200, REWRITE_DONE), respond(status, "{}"));

    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.moveAcrossBuckets("b", "o", "c", "p"))
        .isNotInstanceOf(AlreadyExistsException.class)
        .satisfies(e -> assertThat(e.status()).isEqualTo(status));
  }

  @Test
  void unreachableServerIsRetryable() {
    server.close();

    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.load("b", "o"))
        .satisfies(e -> assertThat(e.status()).isZero())
        .satisfies(e -> assertThat(e.retryable()).isTrue());
  }

  static Stream<Arguments> credentialFailures() {
    return Stream.of(
        Arguments.of(new IOException("unreachable"), true),
        Arguments.of(new AuthFailure(true), true),
        Arguments.of(new AuthFailure(false), false));
  }

  @ParameterizedTest
  @MethodSource("credentialFailures")
  void credentialsFailureSendsNothing(IOException failure, boolean retryable) {
    var credentials =
        new GoogleCredentials() {
          @Override
          public AccessToken refreshAccessToken() throws IOException {
            throw failure;
          }
        };

    try (var unauthorized = client(credentials, Duration.ofSeconds(5), true, 16)) {
      assertThatExceptionOfType(GcsClientException.class)
          .isThrownBy(() -> unauthorized.load("b", "o"))
          .satisfies(e -> assertThat(e.retryable()).isEqualTo(retryable));
    }
    assertThat(server.requests()).isEmpty();
  }

  @Test
  void closedClientRejectsCalls() {
    client.close();
    var files = Map.of("o", HELLO);

    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.load("b", "o"))
        .withMessage("Client is closed")
        .satisfies(e -> assertThat(e.retryable()).isFalse());
    assertThatExceptionOfType(GcsClientException.class)
        .isThrownBy(() -> client.saveAll("b", files, null))
        .withMessage("Client is closed");
    assertThat(server.requests()).isEmpty();
  }

  /** An auth error that, like google-auth's, says whether retrying may help. */
  private static final class AuthFailure extends IOException implements Retryable {

    private final boolean retryable;

    AuthFailure(boolean retryable) {
      super("token refresh failed");
      this.retryable = retryable;
    }

    @Override
    public boolean isRetryable() {
      return retryable;
    }

    @Override
    public int getRetryCount() {
      return 0;
    }
  }
}
