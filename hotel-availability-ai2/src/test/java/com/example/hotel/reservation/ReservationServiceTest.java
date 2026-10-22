package com.example.hotel.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotel.dto.*;
import com.example.hotel.exception.ReservationRejectedException;
import com.example.hotel.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReservationServiceTest {
  final ResilientReservationGateway gateway = mock(ResilientReservationGateway.class);
  final ReservationService service = new ReservationService(gateway, new SimpleMeterRegistry());
  final ReservationRequest request =
      new ReservationRequest(
          "h1",
          "r1",
          LocalDate.of(2027, 1, 1),
          LocalDate.of(2027, 1, 2),
          2,
          "Alex Guest",
          "alex@example.com");

  @Test
  void timeoutDoesNotClaimTheBookingFailed() {
    when(gateway.create(request, "booking-key-001"))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException()));
    var result = service.create(request, "booking-key-001");
    assertThat(result.errorCode()).isEqualTo("RESERVATION_OUTCOME_UNKNOWN");
    assertThat(result.retryable()).isTrue();
    assertThat(result.message()).contains("SAME Idempotency-Key");
  }

  @Test
  void invalidKeyNeverDispatchesAWrite() {
    assertThat(service.create(request, null).errorCode()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    verifyNoInteractions(gateway);
  }

  @Test
  void keyPayloadConflictIsNotRetryable() {
    when(gateway.create(request, "booking-key-001"))
        .thenReturn(
            CompletableFuture.failedFuture(
                new ReservationRejectedException("IDEMPOTENCY_CONFLICT")));
    var result = service.create(request, "booking-key-001");
    assertThat(result.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(result.retryable()).isFalse();
  }

  @Test
  void interruptionRestoresFlagAndPreservesUnknownOutcome() {
    when(gateway.create(request, "booking-key-001")).thenReturn(new CompletableFuture<>());
    Thread.currentThread().interrupt();
    try {
      assertThat(service.create(request, "booking-key-001").errorCode())
          .isEqualTo("RESERVATION_OUTCOME_UNKNOWN");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
