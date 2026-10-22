package com.example.hotel.exception;

public class DuplicateToolCallException extends RuntimeException {
  public DuplicateToolCallException() {
    super("Identical reservation tool call already attempted in this execution.");
  }
}
