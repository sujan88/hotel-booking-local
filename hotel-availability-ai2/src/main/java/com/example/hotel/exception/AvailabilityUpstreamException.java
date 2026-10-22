package com.example.hotel.exception;

public class AvailabilityUpstreamException extends RuntimeException {
  public AvailabilityUpstreamException(String message) {
    super(message);
  }

  public AvailabilityUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
