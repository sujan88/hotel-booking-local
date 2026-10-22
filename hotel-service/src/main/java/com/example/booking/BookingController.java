package com.example.booking;

import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
public class BookingController {
  private final BookingService service;

  public BookingController(BookingService service) {
    this.service = service;
  }

  @PostMapping("/reservations")
  public ResponseEntity<ReservationView> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody BookingRequest request) {
    var result = service.create(request, key);
    return ResponseEntity.status(
            Set.of("PENDING_PAYMENT", "PAYMENT_UNKNOWN").contains(result.status()) ? 202 : 200)
        .body(result);
  }

  @GetMapping("/reservations/{id}")
  public ReservationView status(@PathVariable String id) {
    return service.status(id);
  }

  @GetMapping("/hotels/{id}/availability")
  public List<Map<String, Object>> search(
      @PathVariable String id,
      @RequestParam LocalDate checkIn,
      @RequestParam LocalDate checkOut,
      @RequestParam int guests) {
    return service.availability(id, checkIn, checkOut, guests);
  }

  @RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, String>> problem(ApiProblem e) {
      return ResponseEntity.status(e.status()).body(Map.of("errorCode", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalid(HttpMessageNotReadableException e) {
      for (Throwable c = e; c != null; c = c.getCause())
        if (c instanceof ApiProblem p) return problem(p);
      return ResponseEntity.badRequest().body(Map.of("errorCode", "INVALID_REQUEST"));
    }
  }
}
