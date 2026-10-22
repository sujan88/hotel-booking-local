package com.example.hotel.exception;

public class ReservationRejectedException extends RuntimeException {
  private final String code;

  public ReservationRejectedException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
