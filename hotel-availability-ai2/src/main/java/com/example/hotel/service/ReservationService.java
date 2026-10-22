package com.example.hotel.service;

import com.example.hotel.dto.*;
import com.example.hotel.exception.*;
import com.example.hotel.reservation.IdempotencyKey;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class ReservationService {
  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
  private final ResilientReservationGateway gateway;
  private final MeterRegistry meters;

  public ReservationService(ResilientReservationGateway gateway, MeterRegistry meters) {
    this.gateway = gateway;
    this.meters = meters;
  }

  public ReservationResponse create(ReservationRequest request, String key) {
    try {
      IdempotencyKey.requireValid(key);
    } catch (IllegalArgumentException e) {
      return ReservationResponse.failure("INVALID_IDEMPOTENCY_KEY", e.getMessage(), false);
    }
    ReservationResponse response;
    try {
      response = ReservationResponse.success(gateway.create(request, key).get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      response = unknown();
    } catch (ExecutionException | RuntimeException e) {
      Throwable cause = e;
      while ((cause instanceof ExecutionException || cause instanceof CompletionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ReservationRejectedException rejected)
        response =
            ReservationResponse.failure(
                rejected.code(),
                "PAYMENT_REQUIRED".equals(rejected.code())
                    ? "Payment is required. Complete the secure payment UI, then submit the token"
                        + " outside the chat message."
                    : "Reservation request was rejected. Reuse an idempotency key only for the same"
                        + " booking details.",
                false);
      else if (cause instanceof CallNotPermittedException)
        response =
            ReservationResponse.failure(
                "CIRCUIT_OPEN",
                "Hotel reservations are temporarily unavailable. Retry this operation with the same"
                    + " Idempotency-Key.",
                true);
      else if (cause instanceof RejectedExecutionException)
        response =
            ReservationResponse.failure(
                "BUSY",
                "Reservation processing is busy. Retry with the same Idempotency-Key.",
                true);
      else response = unknown();
      log.warn(
          "Reservation attempt ended: code={}, cause={}",
          response.errorCode(),
          cause.getClass().getSimpleName());
    }
    meters
        .counter(
            "hotel.reservation.results",
            "outcome",
            response.success() ? "SUCCESS" : response.errorCode())
        .increment();
    return response;
  }

  public ReservationResponse status(String id) {
    try {
      return ReservationResponse.success(gateway.status(id).get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unknown();
    } catch (ExecutionException | RuntimeException e) {
      Throwable cause = e;
      while ((cause instanceof ExecutionException || cause instanceof CompletionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ReservationRejectedException rejected)
        return ReservationResponse.failure(
            rejected.code(), "Reservation status could not be retrieved.", false);
      return unknown();
    }
  }

  private ReservationResponse unknown() {
    return ReservationResponse.failure(
        "RESERVATION_OUTCOME_UNKNOWN",
        "The reservation outcome could not be confirmed. Retry or reconcile using the SAME"
            + " Idempotency-Key; do not create a new booking operation.",
        true);
  }
}
