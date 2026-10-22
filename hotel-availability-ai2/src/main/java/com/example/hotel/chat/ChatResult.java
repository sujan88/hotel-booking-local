package com.example.hotel.chat;

import com.example.hotel.dto.ReservationResponse;
import java.util.List;

public record ChatResult(
    boolean success, String message, String errorCode, List<ReservationResponse> reservations) {
  public ChatResult {
    reservations = List.copyOf(reservations);
  }

  public static ChatResult success(String text) {
    return new ChatResult(true, text, null, List.of());
  }

  public static ChatResult failure(String code, String message) {
    return new ChatResult(false, message, code, List.of());
  }

  public ChatResult withReservations(List<ReservationResponse> results) {
    return new ChatResult(success, message, errorCode, results);
  }
}
