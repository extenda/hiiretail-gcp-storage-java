package com.retailsvc.gcp.storage;

import com.google.auth.Credentials;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.Predicate;

/**
 * Creates {@link GcsClient}s for GCS, or for an emulator when {@value #EMULATOR_HOST} is set.
 * Settings are read from system properties first, then environment variables; blank values are
 * ignored.
 */
public class GcsClientFactory {

  /** Emulator URL including scheme, e.g. {@code http://localhost:9023}. */
  public static final String EMULATOR_HOST = "STORAGE_EMULATOR_HOST";

  /** Deadline in seconds for each request, including the response body. Defaults to 60. */
  public static final String REQUEST_TIMEOUT_SECONDS = "STORAGE_REQUEST_TIMEOUT_SECONDS";

  static final String GCS_ENDPOINT = "https://storage.googleapis.com";
  static final String READ_WRITE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
  private static final int DEFAULT_MAX_CONCURRENCY = 16;
  private static final ThreadFactory IO_THREADS =
      Thread.ofVirtual().name("gcs-client-", 0).factory();

  private Duration requestTimeout;
  private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

  /** The most uploads {@link GcsClient#saveAll} runs at once. Defaults to 16. */
  public GcsClientFactory withMaxConcurrency(int maxConcurrency) {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("maxConcurrency must be positive: " + maxConcurrency);
    }
    this.maxConcurrency = maxConcurrency;
    return this;
  }

  /** Overrides {@value #REQUEST_TIMEOUT_SECONDS}. */
  public GcsClientFactory withRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = positive(Objects.requireNonNull(requestTimeout), "requestTimeout");
    return this;
  }

  public GcsClient create() {
    var timeout =
        Optional.ofNullable(requestTimeout)
            .or(() -> setting(REQUEST_TIMEOUT_SECONDS).map(GcsClientFactory::timeoutSetting))
            .orElse(DEFAULT_REQUEST_TIMEOUT);
    var emulator = setting(EMULATOR_HOST);
    // The JDK's default executor is a cached pool of platform threads; run the I/O on virtual ones.
    var http =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .executor(task -> IO_THREADS.newThread(task).start());
    // Plain-HTTP emulators don't speak HTTP/2; skip the h2c upgrade attempt.
    emulator.ifPresent(host -> http.version(HttpClient.Version.HTTP_1_1));
    Credentials credentials =
        emulator.isPresent()
            ? GoogleCredentials.create(new AccessToken("emulator", null))
            : applicationDefault();
    return new GcsClientImpl(
        http.build(),
        credentials,
        emulator.orElse(GCS_ENDPOINT),
        timeout,
        emulator.isEmpty(),
        maxConcurrency);
  }

  static Optional<String> setting(String name) {
    return Optional.ofNullable(System.getProperty(name))
        .filter(Predicate.not(String::isBlank))
        .or(() -> Optional.ofNullable(System.getenv(name)).filter(Predicate.not(String::isBlank)));
  }

  private static Duration timeoutSetting(String seconds) {
    try {
      return positive(Duration.ofSeconds(Long.parseLong(seconds.strip())), REQUEST_TIMEOUT_SECONDS);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          REQUEST_TIMEOUT_SECONDS + " must be a number of seconds: " + seconds, e);
    }
  }

  private static Duration positive(Duration timeout, String name) {
    if (!timeout.isPositive()) {
      throw new IllegalArgumentException(name + " must be positive: " + timeout);
    }
    return timeout;
  }

  private static Credentials applicationDefault() {
    try {
      return GoogleCredentials.getApplicationDefault().createScoped(READ_WRITE_SCOPE);
    } catch (IOException e) {
      throw new GcsClientException("No application default credentials", 0, false, e);
    }
  }
}
