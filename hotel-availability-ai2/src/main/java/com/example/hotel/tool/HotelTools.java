package com.example.hotel.tool;

import com.example.hotel.dto.*;
import com.example.hotel.service.AvailabilityService;
import java.time.LocalDate;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class HotelTools {
  private final AvailabilityService service;

  public HotelTools(AvailabilityService service) {
    this.service = service;
  }

  @Tool(
      description =
          "Search live hotel availability. Never invent rooms or prices. An unsuccessful response"
              + " is not an empty availability result. Do not automatically repeat failed"
              + " searches.")
  public AvailabilityResponse searchAvailability(
      @ToolParam(description = "Hotel identifier") String hotelId,
      @ToolParam(description = "Check-in date, YYYY-MM-DD") String checkIn,
      @ToolParam(description = "Check-out date, YYYY-MM-DD") String checkOut,
      @ToolParam(description = "Number of guests, 1–20") int guests) {
    AvailabilityRequest request;
    try {
      request =
          new AvailabilityRequest(
              hotelId, LocalDate.parse(checkIn), LocalDate.parse(checkOut), guests);
    } catch (RuntimeException e) {
      return AvailabilityResponse.failure(
          "INVALID_REQUEST", "Provide a valid hotel ID, ISO dates and 1–20 guests.", false);
    }
    return service.search(request);
  }
}
