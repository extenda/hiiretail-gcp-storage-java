package com.retailsvc.gcp.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GcsClientIT {

  private static final String BUCKET = "test-bucket";
  private static final String OTHER_BUCKET = "other-bucket";
  private static final int PORT = 9023;

  @Container
  static final GenericContainer<?> EMULATOR =
      new GenericContainer<>(DockerImageName.parse("oittaa/gcp-storage-emulator:v2026.07.19"))
          .withCommand("start", "--default-bucket=" + BUCKET, "--host=0.0.0.0", "--port=" + PORT)
          .withExposedPorts(PORT)
          .waitingFor(Wait.forHttp("/"));

  private GcsClient client;

  @BeforeAll
  static void pointAtEmulator() throws Exception {
    var host = "http://%s:%d".formatted(EMULATOR.getHost(), EMULATOR.getMappedPort(PORT));
    System.setProperty(GcsClientFactory.EMULATOR_HOST, host);
    try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      var createBucket =
          HttpRequest.newBuilder(URI.create(host + "/storage/v1/b?project=test-project"))
              .header("Content-Type", "application/json")
              .POST(BodyPublishers.ofString("{\"name\":\"%s\"}".formatted(OTHER_BUCKET)))
              .build();
      assertThat(http.send(createBucket, BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
  }

  @AfterAll
  static void clearEmulator() {
    System.clearProperty(GcsClientFactory.EMULATOR_HOST);
  }

  @BeforeEach
  void setUp() {
    client = new GcsClientFactory().create();
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  private static String uniqueName(String prefix) {
    return prefix + "/" + UUID.randomUUID() + "/a b.txt";
  }

  @Test
  void saveThenLoadRoundTrips() {
    var name = uniqueName("save");
    var content = "hello".getBytes(UTF_8);

    client.save(BUCKET, name, content, "text/plain");

    assertThat(client.load(BUCKET, name)).contains(content);
  }

  @Test
  void overwriteReplacesContent() {
    var name = uniqueName("overwrite");
    client.save(BUCKET, name, "old".getBytes(UTF_8), null);

    client.overwrite(BUCKET, name, "new".getBytes(UTF_8), null);

    assertThat(client.load(BUCKET, name)).contains("new".getBytes(UTF_8));
  }

  @Test
  void loadOfMissingFileIsEmpty() {
    assertThat(client.load(BUCKET, uniqueName("missing"))).isEmpty();
  }

  @Test
  void saveAllSavesEveryFile() {
    var files =
        Map.of(uniqueName("all"), "one".getBytes(UTF_8), uniqueName("all"), "two".getBytes(UTF_8));

    assertThat(client.saveAll(BUCKET, files, null)).isEmpty();
    files.forEach((name, content) -> assertThat(client.load(BUCKET, name)).contains(content));
  }

  @Test
  void deleteReportsWhetherFileExisted() {
    var name = uniqueName("delete");
    client.save(BUCKET, name, "x".getBytes(UTF_8), null);

    assertThat(client.delete(BUCKET, name)).isTrue();
    assertThat(client.load(BUCKET, name)).isEmpty();
    assertThat(client.delete(BUCKET, name)).isFalse();
  }

  @Test
  void moveWithinBucketLeavesOnlyTheTarget() {
    var from = uniqueName("move");
    var to = uniqueName("moved");
    var content = "moving".getBytes(UTF_8);
    client.save(BUCKET, from, content, null);

    client.move(BUCKET, from, to);

    assertThat(client.load(BUCKET, from)).isEmpty();
    assertThat(client.load(BUCKET, to)).contains(content);
  }

  @Test
  void moveAcrossBucketsLeavesOnlyTheTarget() {
    var from = uniqueName("move");
    var to = uniqueName("moved");
    var content = "moving".getBytes(UTF_8);
    client.save(BUCKET, from, content, null);

    client.moveAcrossBuckets(BUCKET, from, OTHER_BUCKET, to);

    assertThat(client.load(BUCKET, from)).isEmpty();
    assertThat(client.load(OTHER_BUCKET, to)).contains(content);
  }
}
