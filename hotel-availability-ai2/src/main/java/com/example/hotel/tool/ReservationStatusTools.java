package com.example.hotel.tool;

import com.example.hotel.dto.ReservationResponse;
import com.example.hotel.reservation.ReservationExecution;
import com.example.hotel.service.ReservationService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class ReservationStatusTools {
  private final ReservationService service;

  public ReservationStatusTools(ReservationService service) {
    this.service = service;
  }

  @Tool(
      description =
          "Read the current state of an existing reservation. Pending payment is not confirmation."
              + " Never repeat creation to check status.")
  public ReservationResponse getReservationStatus(
      @ToolParam(description = "Existing reservation identifier") String reservationId,
      ToolContext context) {
    return ReservationExecution.capture(context, service.status(reservationId));
  }
}
