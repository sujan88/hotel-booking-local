package com.example.booking;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record BookingRequest(
    String hotelId,
    String roomId,
    LocalDate checkIn,
    LocalDate checkOut,
    int guests,
    String guestName,
    String guestEmail,
    String paymentMethodToken) {
  public BookingRequest {
    hotelId = hotelId == null ? null : hotelId.strip();
    roomId = roomId == null ? null : roomId.strip();
    guestName = guestName == null ? null : guestName.strip().replaceAll("\\s+", " ");
    guestEmail = guestEmail == null ? null : guestEmail.strip();
    if (hotelId == null
        || !hotelId.matches("[A-Za-z0-9_-]{1,80}")
        || roomId == null
        || !roomId.matches("[A-Za-z0-9_-]{1,80}")
        || checkIn == null
        || checkOut == null
        || !checkOut.isAfter(checkIn)
        || ChronoUnit.DAYS.between(checkIn, checkOut) > 30
        || guests < 1
        || guests > 20
        || guestName == null
        || guestName.isBlank()
        || guestName.length() > 120
        || guestEmail == null
        || guestEmail.length() > 254
        || !guestEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
      throw new ApiProblem(400, "INVALID_REQUEST");
    if (paymentMethodToken == null || paymentMethodToken.isBlank())
      throw new ApiProblem(402, "PAYMENT_REQUIRED");
    if (!paymentMethodToken.matches("pm_[A-Za-z0-9_-]{8,90}"))
      throw new ApiProblem(400, "INVALID_PAYMENT_TOKEN");
  }
}
