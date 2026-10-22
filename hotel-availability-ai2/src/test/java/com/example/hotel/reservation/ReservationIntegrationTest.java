package com.example.hotel.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.dto.*;
import com.example.hotel.service.ReservationService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-key",
      "resilience4j.retry.instances.reservation.waitDuration=10ms"
    })
@AutoConfigureMockMvc
class ReservationIntegrationTest {
  static final MockHotelProcess provider = MockHotelProcess.start();
  static final RestClient admin = RestClient.create(provider.baseUrl);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("hotel.api.base-url", () -> provider.baseUrl);
  }

  @Autowired ReservationService service;
  @Autowired CircuitBreakerRegistry circuits;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  final ReservationRequest request =
      new ReservationRequest(
          "h1",
          "r1",
          LocalDate.of(2027, 1, 1),
          LocalDate.of(2027, 1, 2),
          2,
          "Alex Guest",
          "alex@example.com");
  String key;
  int before;

  @BeforeEach
  void reset() {
    mode("success");
    circuits.circuitBreaker("reservation").reset();
    key = UUID.randomUUID().toString();
    before = count();
  }

  @AfterAll
  static void stop() {
    provider.close();
  }

  static void mode(String mode) {
    admin.post().uri("/admin/mode").body(Map.of("mode", mode)).retrieve().toBodilessEntity();
  }

  static Map<?, ?> stats() {
    return admin.get().uri("/admin/reservations").retrieve().body(Map.class);
  }

  static int count() {
    return ((Number) stats().get("reservationCount")).intValue();
  }

  static int attempts() {
    return ((Number) stats().get("writeAttempts")).intValue();
  }

  @Test
  void successAndReplayReturnOneReservation() {
    var first = service.create(request, key);
    var replay = service.create(request, key);
    assertThat(first.success()).isTrue();
    assertThat(replay.reservationId()).isEqualTo(first.reservationId());
    assertThat(count()).isEqualTo(before + 1);
    assertThat(attempts()).isEqualTo(2);
  }

  @Test
  void changedPayloadWithSameKeyConflictsWithoutRetryOrAnotherSideEffect() {
    service.create(request, key);
    var changed =
        new ReservationRequest(
            "h1",
            "r2",
            request.checkIn(),
            request.checkOut(),
            2,
            request.guestName(),
            request.guestEmail());
    var result = service.create(changed, key);
    assertThat(result.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(result.retryable()).isFalse();
    assertThat(attempts()).isEqualTo(2);
    assertThat(count()).isEqualTo(before + 1);
  }

  @Test
  void concurrentClientsWithSameKeyCreateOneSideEffect() throws Exception {
    var workers = Executors.newFixedThreadPool(4);
    try {
      var calls = new ArrayList<Future<ReservationResponse>>();
      for (int i = 0; i < 4; i++) calls.add(workers.submit(() -> service.create(request, key)));
      var ids = new HashSet<String>();
      for (var call : calls) {
        var result = call.get(8, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();
        ids.add(result.reservationId());
      }
      assertThat(ids).hasSize(1);
      assertThat(count()).isEqualTo(before + 1);
      assertThat(attempts()).isEqualTo(4);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void retryAfterCommitted503ReplaysSameKeyWithoutCreatingAgain() {
    mode("commit-then-fail");
    var result = service.create(request, key);
    assertThat(result.success()).isTrue();
    assertThat(attempts()).isEqualTo(2);
    assertThat(count()).isEqualTo(before + 1);
  }

  @Test
  void retryAfterCommittedButTimedOutResponseCreatesOnlyOnce() {
    mode("commit-then-timeout");
    var result = service.create(request, key);
    assertThat(result.success()).isTrue();
    assertThat(attempts()).isEqualTo(2);
    assertThat(count()).isEqualTo(before + 1);
  }

  @Test
  void transientFailuresRetryAndReportAnUnknownOutcome() {
    mode("failure");
    var result = service.create(request, key);
    assertThat(result.errorCode()).isEqualTo("RESERVATION_OUTCOME_UNKNOWN");
    assertThat(result.retryable()).isTrue();
    assertThat(attempts()).isEqualTo(3);
    assertThat(count()).isEqualTo(before);
  }

  @Test
  void nonRetryable400MakesOneAttempt() {
    mode("bad-request");
    assertThat(service.create(request, key).errorCode()).isEqualTo("INVALID_REQUEST");
    assertThat(attempts()).isEqualTo(1);
    assertThat(count()).isEqualTo(before);
  }

  @Test
  void openCircuitDoesNotSendAWrite() {
    circuits.circuitBreaker("reservation").transitionToOpenState();
    assertThat(service.create(request, key).errorCode()).isEqualTo("CIRCUIT_OPEN");
    assertThat(attempts()).isZero();
    assertThat(count()).isEqualTo(before);
  }

  @Test
  void directPostEndpointSupportsReplayAndRejectsMissingKeys() throws Exception {
    String body = mapper.writeValueAsString(request);
    var first =
        mvc.perform(
                post("/api/reservations")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
    String id =
        mapper.readTree(first.getResponse().getContentAsString()).path("reservationId").asText();
    mvc.perform(
            post("/api/reservations")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservationId").value(id));
    mvc.perform(post("/api/reservations").contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/reservations")
                .header("Idempotency-Key", "bad")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/reservations")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body.replace("r1", "r2")))
        .andExpect(status().isConflict());
    assertThat(count()).isEqualTo(before + 1);
  }
}
