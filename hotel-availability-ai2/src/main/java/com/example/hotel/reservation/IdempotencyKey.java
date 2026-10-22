package com.example.hotel.reservation;

public final class IdempotencyKey {
  private IdempotencyKey() {}

  public static String requireValid(String key) {
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
      throw new IllegalArgumentException(
          "Idempotency-Key must be 8–128 ASCII letters, digits, dots, underscores, colons or"
              + " hyphens.");
    return key;
  }
}
