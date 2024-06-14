package com.retailsvc.gcp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class ChecksumTest {

  @Test
  void testProducedChecksum() {
    var input =
        """
    Lorem ipsum dolor sit amet, consectetur adipiscing elit.
    Donec porttitor, turpis id pretium dignissim, mauris nulla
    elementum neque, quis tincidunt dui lacus porttitor arcu.
    Proin quam tellus, tristique non eros quis, pharetra tempus
    libero. Cras efficitur dapibus vehicula.
    Integer pellentesque massa augue, id aliquam velit fermentum ut.
    """
            .getBytes();
    var crc32c = assertDoesNotThrow(() -> Checksum.crc32cBase64(input));
    assertThat(crc32c).isEqualTo("aaOBSA==");
  }

  @Test
  void testNullThrows() {
    assertThatException().isThrownBy(() -> Checksum.crc32cBase64(null));
  }
}
