package com.retailsvc.gcp.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChecksumTest {

  @Test
  void checksumOfText() {
    var input =
        """
        Lorem ipsum dolor sit amet, consectetur adipiscing elit.
        Donec porttitor, turpis id pretium dignissim, mauris nulla
        elementum neque, quis tincidunt dui lacus porttitor arcu.
        Proin quam tellus, tristique non eros quis, pharetra tempus
        libero. Cras efficitur dapibus vehicula.
        Integer pellentesque massa augue, id aliquam velit fermentum ut.
        """
            .getBytes(UTF_8);

    assertThat(Checksum.crc32cBase64(input)).isEqualTo("aaOBSA==");
  }

  @ParameterizedTest
  @CsvSource({"'', AAAAAA==", "123456789, 4waSgw==", "a, wdBDMA=="})
  void knownValues(String input, String expected) {
    assertThat(Checksum.crc32cBase64(input.getBytes(UTF_8))).isEqualTo(expected);
  }

  @Test
  void rejectsNull() {
    assertThatNullPointerException().isThrownBy(() -> Checksum.crc32cBase64(null));
  }
}
