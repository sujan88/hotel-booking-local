package com.example.hotel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

public record ReservationRequest(
    String hotelId,
    String roomId,
    LocalDate checkIn,
    LocalDate checkOut,
    int guests,
    String guestName,
    String guestEmail,
    @JsonInclude(JsonInclude.Include.NON_NULL) String paymentMethodToken) {
  public ReservationRequest(
      String hotelId,
      String roomId,
      LocalDate checkIn,
      LocalDate checkOut,
      int guests,
      String guestName,
      String guestEmail) {
    this(hotelId, roomId, checkIn, checkOut, guests, guestName, guestEmail, null);
  }

  public ReservationRequest withPaymentMethodToken(String token) {
    return new ReservationRequest(
        hotelId, roomId, checkIn, checkOut, guests, guestName, guestEmail, token);
  }

  public ReservationRequest {
    if (paymentMethodToken != null && !paymentMethodToken.matches("pm_[A-Za-z0-9_-]{8,90}"))
      throw new IllegalArgumentException("Invalid payment token");
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
        || guests < 1
        || guests > 20
        || guestName == null
        || guestName.isBlank()
        || guestName.length() > 120
        || guestEmail == null
        || guestEmail.length() > 254
        || !guestEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
      throw new IllegalArgumentException(
          "Provide valid hotel, room, dates, guests, guest name and email.");
  }
}
