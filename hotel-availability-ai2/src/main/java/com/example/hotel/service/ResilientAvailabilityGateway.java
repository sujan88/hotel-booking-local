package com.example.hotel.service;

import com.example.hotel.client.HotelApiClient;
import com.example.hotel.dto.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ResilientAvailabilityGateway {
  private final HotelApiClient client;
  private final Executor executor;

  public ResilientAvailabilityGateway(
      HotelApiClient client, @Qualifier("availabilityExecutor") Executor executor) {
    this.client = client;
    this.executor = executor;
  }

  @CircuitBreaker(name = "availability")
  @Retry(name = "availability")
  @TimeLimiter(name = "availability")
  public CompletableFuture<AvailabilityResponse> search(AvailabilityRequest request) {
    try {
      return CompletableFuture.supplyAsync(() -> client.search(request), executor);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
