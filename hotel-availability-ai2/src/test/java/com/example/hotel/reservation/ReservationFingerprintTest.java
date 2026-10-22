package com.example.hotel.reservation;

import static org.assertj.core.api.Assertions.*;

import com.example.hotel.exception.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import tools.jackson.databind.ObjectMapper;

class ReservationFingerprintTest {
  static final String ARGS =
      "{\"hotelId\":\"h1\",\"roomId\":\"r1\",\"checkIn\":\"2027-01-01\",\"checkOut\":\"2027-01-02\",\"guests\":2,\"guestName\":\"Alex"
          + " Guest\",\"guestEmail\":\"alex@example.com\"}";
  final ReservationFingerprint fingerprints = new ReservationFingerprint(new ObjectMapper());

  static ToolCall call(String args) {
    return new ToolCall("id", "function", "createReservation", args);
  }

  @Test
  void normalizesOrderNumbersAndWhitespace() {
    String other =
        "{\"guestEmail\":\" alex@example.com \",\"guestName\":\" Alex   Guest"
            + " \",\"guests\":2.0,\"checkOut\":\"2027-01-02\",\"checkIn\":\"2027-01-01\",\"roomId\":\""
            + " r1 \",\"hotelId\":\"h1\"}";
    assertThat(fingerprints.of(call(ARGS))).isEqualTo(fingerprints.of(call(other)));
  }

  @Test
  void includesToolNameAndEveryBookingField() {
    String original = fingerprints.of(call(ARGS));
    assertThat(fingerprints.of(call(ARGS.replace("r1", "r2")))).isNotEqualTo(original);
    assertThat(fingerprints.of(new ToolCall("id", "function", "otherWrite", ARGS)))
        .isNotEqualTo(original);
    assertThat(fingerprints.of(call(ARGS.replace("alex@example.com", "other@example.com"))))
        .isNotEqualTo(original);
  }

  @Test
  void rejectsExtraFieldsAndDuplicateJsonKeys() {
    assertThatThrownBy(
            () -> fingerprints.of(call(ARGS.replace("}", ",\"idempotencyKey\":\"model-key\"}"))))
        .isInstanceOf(InvalidReservationToolCallException.class);
    assertThatThrownBy(
            () -> fingerprints.of(call(ARGS.replace("\"guests\":2", "\"guests\":2,\"guests\":3"))))
        .isInstanceOf(InvalidReservationToolCallException.class);
  }

  @Test
  void duplicateBatchIsRejectedAtomicallyAndContextsAreIndependent() {
    var first = new ReservationExecution("booking-test-001");
    assertThatThrownBy(() -> first.claimBatch(List.of("a", "a")))
        .isInstanceOf(DuplicateToolCallException.class);
    first.claimBatch(List.of("a"));
    assertThatThrownBy(() -> first.claimBatch(List.of("a")))
        .isInstanceOf(DuplicateToolCallException.class);
    new ReservationExecution("booking-test-001").claimBatch(List.of("a"));
  }

  @Test
  void concurrentClaimsAdmitOnlyOne() throws Exception {
    var execution = new ReservationExecution("booking-test-001");
    var workers = Executors.newFixedThreadPool(4);
    try {
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 4; i++)
        results.add(
            workers.submit(
                () -> {
                  try {
                    execution.claimBatch(List.of("same"));
                    return true;
                  } catch (DuplicateToolCallException e) {
                    return false;
                  }
                }));
      int accepted = 0;
      for (var result : results) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(1);
    } finally {
      workers.shutdownNow();
    }
  }
}
