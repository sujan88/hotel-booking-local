package com.example.hotel.reservation;

import com.example.hotel.dto.*;
import com.example.hotel.service.ReservationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
  private final ReservationService service;

  public ReservationController(ReservationService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ReservationResponse> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody ReservationRequest request) {
    var response = service.create(request, key);
    int status;
    if (response.success()) {
      status =
          switch (response.status()) {
            case "PENDING_PAYMENT", "PAYMENT_UNKNOWN" -> 202;
            default -> 200;
          };
    } else {
      status =
          switch (response.errorCode()) {
            case "PAYMENT_REQUIRED" -> 402;
            case "IDEMPOTENCY_CONFLICT", "INVENTORY_UNAVAILABLE" -> 409;
            case "INVALID_REQUEST", "INVALID_IDEMPOTENCY_KEY" -> 400;
            default -> 503;
          };
    }
    return ResponseEntity.status(status).body(response);
  }

  @GetMapping("/{id}")
  public ResponseEntity<ReservationResponse> status(@PathVariable String id) {
    var response = service.status(id);
    return ResponseEntity.status(
            response.success()
                ? 200
                : "RESERVATION_NOT_FOUND".equals(response.errorCode()) ? 404 : 503)
        .body(response);
  }
}
