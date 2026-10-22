package com.example.hotel.tool;

import com.example.hotel.dto.*;
import com.example.hotel.reservation.ReservationExecution;
import com.example.hotel.service.ReservationService;
import java.time.LocalDate;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class ReservationTools {
  private final ReservationService service;

  public ReservationTools(ReservationService service) {
    this.service = service;
  }

  @Tool(
      description =
          "Create a reservation only when the user explicitly requests booking and provides the"
              + " hotel ID, room ID, dates, guest count, guest name and email. Do not invent"
              + " details or repeat a booking call. An unknown outcome is not proof the reservation"
              + " failed.")
  public ReservationResponse createReservation(
      @ToolParam(description = "Hotel identifier") String hotelId,
      @ToolParam(description = "Room identifier from availability") String roomId,
      @ToolParam(description = "Check-in YYYY-MM-DD") String checkIn,
      @ToolParam(description = "Check-out YYYY-MM-DD") String checkOut,
      @ToolParam(description = "Guest count, 1–20") int guests,
      @ToolParam(description = "Guest full name") String guestName,
      @ToolParam(description = "Guest email address") String guestEmail,
      ToolContext context) {
    if (context == null
        || !(context.getContext().get(ReservationExecution.CONTEXT_KEY)
            instanceof ReservationExecution execution)
        || !execution.canCreateReservation())
      return ReservationResponse.failure(
          "INVALID_IDEMPOTENCY_KEY",
          "A client Idempotency-Key is required to create a reservation.",
          false);
    ReservationRequest request;
    try {
      request =
          new ReservationRequest(
              hotelId,
              roomId,
              LocalDate.parse(checkIn),
              LocalDate.parse(checkOut),
              guests,
              guestName,
              guestEmail);
    } catch (RuntimeException e) {
      return ReservationResponse.failure(
          "INVALID_REQUEST", "Provide complete valid booking details.", false);
    }
    return ReservationExecution.capture(
        context,
        service.create(
            request.withPaymentMethodToken(execution.paymentMethodToken()),
            execution.idempotencyKey()));
  }
}
