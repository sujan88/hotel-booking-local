package com.example.hotel.dto;

import java.time.LocalDate;

public record AvailabilityRequest(
    String hotelId, LocalDate checkIn, LocalDate checkOut, int guests) {
  public AvailabilityRequest {
    if (hotelId == null
        || !hotelId.matches("[A-Za-z0-9_-]{1,80}")
        || checkIn == null
        || checkOut == null
        || !checkOut.isAfter(checkIn)
        || guests < 1
        || guests > 20)
      throw new IllegalArgumentException("Provide a valid hotel ID, date range and 1–20 guests.");
  }
}
