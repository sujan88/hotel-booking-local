package com.example.booking;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:saga;DB_CLOSE_DELAY=-1",
      "saga.worker-enabled=false",
      "saga.retry-ms=60000",
      "payment.timeout-ms=250"
    })
class SagaIntegrationTest {
  @Autowired BookingService bookings;
  @Autowired OutboxWorker worker;
  @Autowired LocalNotificationConsumers notifications;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  @LocalServerPort int port;
  RestClient http;
  String token;

  @BeforeEach
  void setup() {
    http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    for (String table :
        List.of(
            "notification_inbox",
            "notification_deliveries",
            "outbox",
            "reservations",
            "simulated_payments",
            "payment_methods")) db.update("DELETE FROM " + table);
    mode("SUCCESS");
    token =
        (String)
            http.post()
                .uri("/simulator/payment-methods")
                .retrieve()
                .body(Map.class)
                .get("paymentMethodToken");
  }

  void mode(String mode) {
    http.post().uri("/simulator/mode").body(Map.of("mode", mode)).retrieve().toBodilessEntity();
  }

  BookingRequest request() {
    return new BookingRequest(
        "h1",
        "r1",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2027, 1, 2),
        2,
        "Alex Guest",
        "alex@example.com",
        token);
  }

  int count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  void ready() {
    db.update("UPDATE outbox SET available_at=0 WHERE state='READY'");
  }

  @Test
  void pendingThenConfirmedAndIndependentConsumers() {
    var reservation = bookings.create(request(), "booking-success-001");
    assertThat(reservation.status()).isEqualTo("PENDING_PAYMENT");
    assertThat(count("outbox")).isOne();
    assertThat(count("simulated_payments")).isZero();
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CONFIRMED");
    assertThat(count("notification_deliveries")).isEqualTo(2);
    assertThat(count("notification_inbox")).isZero();
    notifications.consume("EMAIL");
    assertThat(count("notification_inbox")).isOne();
    notifications.consume("PMS");
    assertThat(count("notification_inbox")).isEqualTo(2);
    assertThat(count("simulated_payments")).isOne();
  }

  @Test
  void declineCancelsAndReleasesInventory() {
    mode("DECLINE");
    var reservation = bookings.create(request(), "booking-decline-001");
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CANCELLED");
    assertThat(db.queryForObject("SELECT held FROM reservations", Boolean.class)).isFalse();
    assertThat(count("notification_deliveries")).isZero();
    assertThat(bookings.create(request(), "booking-after-decline").status())
        .isEqualTo("PENDING_PAYMENT");
  }

  @Test
  void committedPaymentTimeoutIsReconciledWithoutSecondCharge() {
    mode("COMMIT_THEN_TIMEOUT");
    var reservation = bookings.create(request(), "booking-ambiguous-001");
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("PAYMENT_UNKNOWN");
    assertThat(count("simulated_payments")).isOne();
    assertThat(db.queryForObject("SELECT held FROM reservations", Boolean.class)).isTrue();
    ready();
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CONFIRMED");
    assertThat(count("simulated_payments")).isOne();
  }

  @Test
  void timeoutBeforeCommitKeepsHoldThenRecoversUsingSamePaymentKey() {
    mode("TIMEOUT_BEFORE_COMMIT");
    var reservation = bookings.create(request(), "booking-timeout-001");
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("PAYMENT_UNKNOWN");
    assertThat(count("simulated_payments")).isZero();
    String key = db.queryForObject("SELECT payment_key FROM reservations", String.class);
    mode("SUCCESS");
    ready();
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CONFIRMED");
    assertThat(db.queryForObject("SELECT payment_key FROM simulated_payments", String.class))
        .isEqualTo(key);
  }

  @Test
  void pendingProviderIsReconciledAfterResolution() {
    mode("PENDING");
    var reservation = bookings.create(request(), "booking-pending-001");
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("PAYMENT_UNKNOWN");
    String key = db.queryForObject("SELECT payment_key FROM reservations", String.class);
    http.post()
        .uri("/simulator/payments/" + key + "/resolve")
        .body(Map.of("status", "SUCCEEDED"))
        .retrieve()
        .toBodilessEntity();
    ready();
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void concurrentReplayCreatesOneReservationAndOnePayment() {
    var pool = Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<Future<ReservationView>>();
      for (int i = 0; i < 4; i++)
        futures.add(pool.submit(() -> bookings.create(request(), "booking-concurrent-001")));
      var ids = new HashSet<String>();
      for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS).reservationId());
      assertThat(ids).hasSize(1);
    } catch (Exception e) {
      throw new AssertionError(e);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("reservations")).isOne();
    worker.runOnce();
    assertThat(count("simulated_payments")).isOne();
  }

  @Test
  void changedPayloadWithSameKeyIsRejected() {
    bookings.create(request(), "booking-conflict-001");
    var r = request();
    var changed =
        new BookingRequest(
            r.hotelId(),
            r.roomId(),
            r.checkIn(),
            r.checkOut(),
            3,
            r.guestName(),
            r.guestEmail(),
            token);
    assertThatThrownBy(() -> bookings.create(changed, "booking-conflict-001"))
        .isInstanceOf(ApiProblem.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThat(count("reservations")).isOne();
    assertThat(count("outbox")).isOne();
  }

  @Test
  void inventoryCannotBeOversold() {
    bookings.create(request(), "booking-first-001");
    assertThatThrownBy(() -> bookings.create(request(), "booking-second-001"))
        .hasMessage("INVENTORY_UNAVAILABLE");
    assertThat(count("reservations")).isOne();
  }

  @Test
  void transactionRollbackLeavesNoReservationHoldOrOutbox() {
    tx.executeWithoutResult(
        t -> {
          bookings.create(request(), "booking-rollback-001");
          t.setRollbackOnly();
        });
    assertThat(count("reservations")).isZero();
    assertThat(count("outbox")).isZero();
    assertThat(bookings.availability("h1", request().checkIn(), request().checkOut(), 2))
        .hasSize(2);
  }

  @Test
  void expiredWorkerLeaseIsRecovered() {
    var reservation = bookings.create(request(), "booking-lease-001");
    db.update("UPDATE outbox SET state='PROCESSING',owner='dead-worker',lease_until=0");
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void crashAfterProviderCommitBeforeConfirmationReplaysSafely() {
    var reservation = bookings.create(request(), "booking-crash-001");
    String key = db.queryForObject("SELECT payment_key FROM reservations", String.class);
    http.post()
        .uri("/simulator/payments")
        .header("Idempotency-Key", key)
        .body(new PaymentClient.PaymentRequest(key, token, 12000, "USD"))
        .retrieve()
        .toBodilessEntity();
    db.update("UPDATE outbox SET state='PROCESSING',owner='crashed-worker',lease_until=0");
    worker.runOnce();
    assertThat(bookings.status(reservation.reservationId()).status()).isEqualTo("CONFIRMED");
    assertThat(count("simulated_payments")).isOne();
  }

  @Test
  void duplicateConfirmationDeliveryDoesNotRepeatConsumerEffects() {
    bookings.create(request(), "booking-notifications-001");
    worker.runOnce();
    notifications.runOnce();
    db.update(
        "UPDATE outbox SET state='READY',available_at=0 WHERE event_type='RESERVATION_CONFIRMED'");
    worker.runOnce();
    db.update("UPDATE notification_deliveries SET state='READY'");
    notifications.runOnce();
    assertThat(count("notification_inbox")).isEqualTo(2);
    assertThat(count("notification_deliveries")).isEqualTo(2);
  }

  @Test
  void paymentRequiredAndHttpStatusLookup() {
    var r = request();
    var body = new HashMap<String, Object>();
    body.put("hotelId", r.hotelId());
    body.put("roomId", r.roomId());
    body.put("checkIn", r.checkIn());
    body.put("checkOut", r.checkOut());
    body.put("guests", r.guests());
    body.put("guestName", r.guestName());
    body.put("guestEmail", r.guestEmail());
    assertThatThrownBy(
            () ->
                http.post()
                    .uri("/reservations")
                    .header("Idempotency-Key", "booking-http-001")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(402));
    body.put("paymentMethodToken", token);
    var result =
        http.post()
            .uri("/reservations")
            .header("Idempotency-Key", "booking-http-001")
            .body(body)
            .retrieve()
            .toEntity(ReservationView.class);
    assertThat(result.getStatusCode().value()).isEqualTo(202);
    worker.runOnce();
    assertThat(
            http.get()
                .uri("/reservations/" + result.getBody().reservationId())
                .retrieve()
                .body(ReservationView.class)
                .status())
        .isEqualTo("CONFIRMED");
  }

  @Test
  void samePaymentKeyCannotChangeAmount() {
    var reservation = bookings.create(request(), "booking-payment-key-001");
    worker.runOnce();
    String key = "pay_" + reservation.reservationId();
    assertThatThrownBy(
            () ->
                http.post()
                    .uri("/simulator/payments")
                    .header("Idempotency-Key", key)
                    .body(new PaymentClient.PaymentRequest(key, token, 24000, "USD"))
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void concurrentDifferentBookingsCannotOversellOneRoom() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      var start = new CountDownLatch(1);
      Callable<Boolean> first =
          () -> {
            start.await();
            try {
              bookings.create(request(), "booking-race-first");
              return true;
            } catch (ApiProblem e) {
              assertThat(e.getMessage()).isEqualTo("INVENTORY_UNAVAILABLE");
              return false;
            }
          };
      Callable<Boolean> second =
          () -> {
            start.await();
            try {
              bookings.create(request(), "booking-race-second");
              return true;
            } catch (ApiProblem e) {
              assertThat(e.getMessage()).isEqualTo("INVENTORY_UNAVAILABLE");
              return false;
            }
          };
      var a = pool.submit(first);
      var b = pool.submit(second);
      start.countDown();
      assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(count("reservations")).isOne();
      assertThat(count("outbox")).isOne();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentWorkersStillProduceOneChargeAndOneEventPerBooking() throws Exception {
    bookings.create(request(), "booking-workers-first");
    var r = request();
    bookings.create(
        new BookingRequest(
            r.hotelId(),
            "r2",
            r.checkIn(),
            r.checkOut(),
            r.guests(),
            r.guestName(),
            r.guestEmail(),
            token),
        "booking-workers-second");
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(worker::runOnce);
      var b = pool.submit(worker::runOnce);
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    worker.runOnce();
    notifications.runOnce();
    assertThat(count("simulated_payments")).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE status='CONFIRMED'", Integer.class))
        .isEqualTo(2);
    assertThat(count("notification_inbox")).isEqualTo(4);
  }
}
