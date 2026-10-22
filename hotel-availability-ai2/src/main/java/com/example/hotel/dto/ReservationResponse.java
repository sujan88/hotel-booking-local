package com.example.hotel.dto;

public record ReservationResponse(
    boolean success,
    String reservationId,
    String status,
    String errorCode,
    String message,
    boolean retryable) {
  public static ReservationResponse success(Reservation reservation) {
    return new ReservationResponse(
        true,
        reservation.reservationId(),
        reservation.status(),
        null,
        switch (reservation.status()) {
          case "CONFIRMED" -> "Reservation confirmed.";
          case "CANCELLED" -> "Reservation cancelled because payment was declined.";
          case "PENDING_PAYMENT" ->
              "Reservation accepted; payment confirmation is pending. Check reservation status.";
          default ->
              "Payment outcome is unresolved. Check this reservation status; do not create a new"
                  + " booking.";
        },
        false);
  }

  public static ReservationResponse failure(String code, String message, boolean retryable) {
    return new ReservationResponse(false, null, null, code, message, retryable);
  }
}
