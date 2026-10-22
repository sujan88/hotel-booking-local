package com.example.hotel.exception;

public class ReservationTransientException extends RuntimeException {
  public ReservationTransientException(String message, Throwable cause) {
    super(message, cause);
  }
}
