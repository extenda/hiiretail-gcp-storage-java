package com.retailsvc.gcp.storage;

import static com.retailsvc.gcp.storage.StubServer.stallBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GcsClientFactoryTest {

  private final StubServer server = new StubServer();

  @BeforeEach
  void setUp() {
    System.setProperty(GcsClientFactory.EMULATOR_HOST, server.url());
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(GcsClientFactory.EMULATOR_HOST);
    System.clearProperty(GcsClientFactory.REQUEST_TIMEOUT_SECONDS);
    server.close();
  }

  @Test
  @Timeout(5)
  void requestTimeoutFromSetting() {
    System.setProperty(GcsClientFactory.REQUEST_TIMEOUT_SECONDS, "1");

    assertStalledCallTimesOut(new GcsClientFactory());
  }

  @Test
  @Timeout(5)
  void factoryTimeoutOverridesSetting() {
    System.setProperty(GcsClientFactory.REQUEST_TIMEOUT_SECONDS, "600");

    assertStalledCallTimesOut(new GcsClientFactory().withRequestTimeout(Duration.ofMillis(200)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "soon"})
  void rejectsInvalidTimeoutSetting(String value) {
    System.setProperty(GcsClientFactory.REQUEST_TIMEOUT_SECONDS, value);
    var factory = new GcsClientFactory();

    assertThatIllegalArgumentException()
        .isThrownBy(factory::create)
        .withMessageContaining(GcsClientFactory.REQUEST_TIMEOUT_SECONDS);
  }

  @Test
  void rejectsNonPositiveRequestTimeout() {
    var factory = new GcsClientFactory();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> factory.withRequestTimeout(Duration.ZERO));
  }

  @Test
  void rejectsNonPositiveConcurrency() {
    var factory = new GcsClientFactory();

    assertThatIllegalArgumentException().isThrownBy(() -> factory.withMaxConcurrency(0));
  }

  @Test
  void ignoresBlankSetting() {
    System.setProperty(GcsClientFactory.EMULATOR_HOST, " ");

    assertThat(GcsClientFactory.setting(GcsClientFactory.EMULATOR_HOST)).isEmpty();
  }

  private void assertStalledCallTimesOut(GcsClientFactory factory) {
    server.enqueue(stallBody());
    try (var client = factory.create()) {
      assertThatExceptionOfType(GcsClientException.class)
          .isThrownBy(() -> client.load("b", "o"))
          .withCauseInstanceOf(TimeoutException.class);
    }
  }
}
