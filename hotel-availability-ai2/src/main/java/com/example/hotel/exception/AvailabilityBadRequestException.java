package com.example.hotel.exception;

public class AvailabilityBadRequestException extends RuntimeException {
  public AvailabilityBadRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
