package com.example.hotel.service;

import com.example.hotel.dto.*;
import com.example.hotel.exception.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {
  private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);
  private final ResilientAvailabilityGateway gateway;
  private final MeterRegistry meters;

  public AvailabilityService(ResilientAvailabilityGateway gateway, MeterRegistry meters) {
    this.gateway = gateway;
    this.meters = meters;
  }

  public AvailabilityResponse search(AvailabilityRequest request) {
    AvailabilityResponse result;
    try {
      result = gateway.search(request).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result = failure("INTERRUPTED", true);
    } catch (ExecutionException | RuntimeException e) {
      Throwable cause = e;
      while ((cause instanceof ExecutionException || cause instanceof CompletionException)
          && cause.getCause() != null) cause = cause.getCause();
      String code =
          cause instanceof AvailabilityBadRequestException
              ? "INVALID_REQUEST"
              : cause instanceof TimeoutException
                  ? "TIMEOUT"
                  : cause instanceof CallNotPermittedException
                      ? "CIRCUIT_OPEN"
                      : cause instanceof RejectedExecutionException
                          ? "BUSY"
                          : cause instanceof AvailabilityTransientException
                              ? "TEMPORARILY_UNAVAILABLE"
                              : "UPSTREAM_ERROR";
      result = failure(code, !code.equals("INVALID_REQUEST") && !code.equals("UPSTREAM_ERROR"));
      log.warn("Availability failed: code={}, cause={}", code, cause.getClass().getSimpleName());
    }
    meters
        .counter(
            "hotel.availability.results",
            "outcome",
            result.success() ? "SUCCESS" : result.errorCode())
        .increment();
    return result;
  }

  private AvailabilityResponse failure(String code, boolean retryable) {
    return AvailabilityResponse.failure(
        code,
        code.equals("INVALID_REQUEST")
            ? "Check the hotel search parameters."
            : "Hotel availability could not be retrieved.",
        retryable);
  }
}
