package com.example.booking;

import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;

final class Hashes {
  static String of(ObjectMapper mapper, Object value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot fingerprint request", e);
    }
  }

  private Hashes() {}
}
