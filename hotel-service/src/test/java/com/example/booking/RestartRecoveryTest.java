package com.example.booking;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

class RestartRecoveryTest {
  @TempDir Path directory;

  @Test
  void committedSagaAndProviderStateSurviveFullApplicationRestart() {
    String url = "jdbc:h2:file:" + directory.resolve("hotel") + ";DB_CLOSE_ON_EXIT=FALSE";
    String[] args = {
      "--server.port=0",
      "--spring.datasource.url=" + url,
      "--saga.worker-enabled=false",
      "--payment.timeout-ms=500"
    };
    String id;
    try (var first = new SpringApplicationBuilder(HotelServiceApplication.class).run(args)) {
      var db = first.getBean(JdbcTemplate.class);
      String token = "pm_restart_simulated_token";
      db.update("INSERT INTO payment_methods(token) VALUES(?)", token);
      id =
          first
              .getBean(BookingService.class)
              .create(
                  new BookingRequest(
                      "h1",
                      "r1",
                      LocalDate.of(2027, 2, 1),
                      LocalDate.of(2027, 2, 2),
                      2,
                      "Alex Guest",
                      "alex@example.com",
                      token),
                  "booking-restart-001")
              .reservationId();
      // Crash window: provider accepted payment, reservation transition not yet committed.
      db.update(
          "INSERT INTO simulated_payments(payment_key,request_hash,status,amount_cents)"
              + " VALUES(?,?,'SUCCEEDED',12000)",
          "pay_" + id,
          "persisted-provider-response");
      db.update("UPDATE outbox SET state='PROCESSING',owner='previous-process',lease_until=0");
    }
    try (var second = new SpringApplicationBuilder(HotelServiceApplication.class).run(args)) {
      assertThat(second.getBean(BookingService.class).status(id).status())
          .isEqualTo("PENDING_PAYMENT");
      second.getBean(OutboxWorker.class).runOnce();
      second.getBean(LocalNotificationConsumers.class).runOnce();
      assertThat(second.getBean(BookingService.class).status(id).status()).isEqualTo("CONFIRMED");
      var db = second.getBean(JdbcTemplate.class);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM simulated_payments", Integer.class))
          .isOne();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox", Integer.class))
          .isEqualTo(2);
    }
  }
}
