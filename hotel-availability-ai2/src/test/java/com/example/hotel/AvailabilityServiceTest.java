package com.example.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotel.dto.AvailabilityRequest;
import com.example.hotel.dto.AvailabilityResponse;
import com.example.hotel.service.AvailabilityService;
import com.example.hotel.service.ResilientAvailabilityGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class AvailabilityServiceTest {
  private final ResilientAvailabilityGateway gateway = mock(ResilientAvailabilityGateway.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AvailabilityService service = new AvailabilityService(gateway, meters);
  private final AvailabilityRequest request =
      new AvailabilityRequest("h1", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2), 2);

  @Test
  void rejectedExecutorProducesBusyAndMetric() {
    when(gateway.search(request))
        .thenReturn(CompletableFuture.failedFuture(new RejectedExecutionException()));
    assertThat(service.search(request).errorCode()).isEqualTo("BUSY");
    assertThat(meters.get("hotel.availability.results").tag("outcome", "BUSY").counter().count())
        .isEqualTo(1);
  }

  @Test
  void interruptFlagIsRestored() {
    when(gateway.search(request)).thenReturn(new CompletableFuture<AvailabilityResponse>());
    Thread.currentThread().interrupt();
    try {
      assertThat(service.search(request).errorCode()).isEqualTo("INTERRUPTED");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
