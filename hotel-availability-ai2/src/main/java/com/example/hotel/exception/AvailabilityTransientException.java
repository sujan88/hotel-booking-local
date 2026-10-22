package com.example.hotel.exception;

public class AvailabilityTransientException extends RuntimeException {
  public AvailabilityTransientException(String message, Throwable cause) {
    super(message, cause);
  }
}
