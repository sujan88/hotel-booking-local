package com.example.hotel.dto;

import java.util.List;

public record AvailabilityResponse(
    boolean success, List<Room> rooms, String errorCode, String message, boolean retryable) {
  public AvailabilityResponse {
    rooms = List.copyOf(rooms);
  }

  public static AvailabilityResponse success(List<Room> rooms) {
    return new AvailabilityResponse(true, rooms, null, null, false);
  }

  public static AvailabilityResponse failure(String code, String message, boolean retryable) {
    return new AvailabilityResponse(false, List.of(), code, message, retryable);
  }
}
