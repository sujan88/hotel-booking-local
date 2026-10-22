package com.example.hotel.exception;

public class ReservationUpstreamException extends RuntimeException {
  public ReservationUpstreamException(String message) {
    super(message);
  }

  public ReservationUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
