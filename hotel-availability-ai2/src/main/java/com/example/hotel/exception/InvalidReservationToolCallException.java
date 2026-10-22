package com.example.hotel.exception;

public class InvalidReservationToolCallException extends RuntimeException {
  public InvalidReservationToolCallException() {
    super("A reservation requires valid arguments and a client-provided Idempotency-Key.");
  }
}
