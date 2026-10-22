package com.example.booking;

public class ApiProblem extends RuntimeException {
  private final int status;

  public ApiProblem(int status, String code) {
    super(code);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
