package com.example.booking;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker {
  private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentClient payments;
  private final BookingService bookings;
  private final MeterRegistry meters;
  private final boolean enabled;
  private final long retryMs, leaseMs;
  private final int batch;

  record Event(String id, String reservationId, String type, String owner) {}

  public OutboxWorker(
      JdbcTemplate db,
      TransactionTemplate tx,
      PaymentClient payments,
      BookingService bookings,
      MeterRegistry meters,
      @Value("${saga.worker-enabled:true}") boolean enabled,
      @Value("${saga.retry-ms:2000}") long retryMs,
      @Value("${saga.lease-ms:30000}") long leaseMs,
      @Value("${saga.batch-size:10}") int batch) {
    this.db = db;
    this.tx = tx;
    this.payments = payments;
    this.bookings = bookings;
    this.meters = meters;
    this.enabled = enabled;
    this.retryMs = retryMs;
    this.leaseMs = leaseMs;
    this.batch = batch;
    if (retryMs < 1 || leaseMs < 1000 || batch < 1 || batch > 100)
      throw new IllegalArgumentException("Invalid worker configuration");
  }

  @Scheduled(fixedDelayString = "${saga.poll-ms:1000}")
  public void poll() {
    if (enabled) runOnce();
  }

  public void runOnce() {
    for (int i = 0; i < batch; i++) {
      Event event = claim();
      if (event == null) return;
      try {
        handle(event);
      } catch (RuntimeException e) {
        log.warn(
            "Saga event processing deferred: type={}, cause={}",
            event.type(),
            e.getClass().getSimpleName());
        tx.executeWithoutResult(t -> retry(event, "WORKER_ERROR"));
      }
    }
  }

  private Event claim() {
    return tx.execute(
        t -> {
          long now = System.currentTimeMillis();
          var events =
              db.query(
                  "SELECT id,reservation_id,event_type FROM outbox WHERE (state='READY' AND"
                      + " available_at<=?) OR (state='PROCESSING' AND lease_until<=?) ORDER BY"
                      + " available_at,id FETCH FIRST 1 ROW ONLY FOR UPDATE",
                  (rs, n) ->
                      new Event(
                          rs.getString(1),
                          rs.getString(2),
                          rs.getString(3),
                          UUID.randomUUID().toString()),
                  now,
                  now);
          if (events.isEmpty()) return null;
          var e = events.get(0);
          db.update(
              "UPDATE outbox SET state='PROCESSING',owner=?,lease_until=?,attempts=attempts+1 WHERE"
                  + " id=?",
              e.owner(),
              now + leaseMs,
              e.id());
          return e;
        });
  }

  private void handle(Event e) {
    if ("PAYMENT_REQUESTED".equals(e.type())) {
      var request =
          db.queryForObject(
              "SELECT payment_key,payment_token,amount_cents FROM reservations WHERE id=?",
              (rs, n) ->
                  new PaymentClient.PaymentRequest(
                      rs.getString(1), rs.getString(2), rs.getLong(3), "USD"),
              e.reservationId());
      // Claim committed above. No database transaction or inventory lock spans this HTTP call.
      String outcome = payments.resolve(request);
      tx.executeWithoutResult(
          t -> {
            if (!own(e)) return;
            String current =
                db.queryForObject(
                    "SELECT status FROM reservations WHERE id=? FOR UPDATE",
                    String.class,
                    e.reservationId());
            if (!Set.of("PENDING_PAYMENT", "PAYMENT_UNKNOWN").contains(current)) {
              done(e);
              return;
            }
            if ("SUCCEEDED".equals(outcome)) {
              db.update(
                  "UPDATE reservations SET status='CONFIRMED',updated_at=? WHERE id=?",
                  System.currentTimeMillis(),
                  e.reservationId());
              bookings.enqueue(e.reservationId(), "RESERVATION_CONFIRMED");
              done(e);
            } else if ("DECLINED".equals(outcome)) {
              db.update(
                  "UPDATE reservations SET status='CANCELLED',held=FALSE,updated_at=? WHERE id=?",
                  System.currentTimeMillis(),
                  e.reservationId());
              done(e);
            } else {
              db.update(
                  "UPDATE reservations SET status='PAYMENT_UNKNOWN',updated_at=? WHERE id=?",
                  System.currentTimeMillis(),
                  e.reservationId());
              retry(e, "PAYMENT_UNRESOLVED");
            }
            meters.counter("hotel.saga.payment.outcomes", "outcome", outcome).increment();
          });
    } else if ("RESERVATION_CONFIRMED".equals(e.type())) {
      tx.executeWithoutResult(
          t -> {
            if (!own(e)) return;
            // Local durable fan-out transport: one delivery per independent consumer.
            for (String channel : List.of("EMAIL", "PMS"))
              db.update(
                  "INSERT INTO notification_deliveries(event_id,channel,reservation_id,state)"
                      + " SELECT ?,?,?,'READY' WHERE NOT EXISTS(SELECT 1 FROM"
                      + " notification_deliveries WHERE event_id=? AND channel=?)",
                  e.id(),
                  channel,
                  e.reservationId(),
                  e.id(),
                  channel);
            done(e);
          });
    } else throw new IllegalStateException("Unknown event type");
  }

  private boolean own(Event e) {
    return !db.queryForList(
            "SELECT id FROM outbox WHERE id=? AND owner=? AND state='PROCESSING' FOR UPDATE",
            String.class,
            e.id(),
            e.owner())
        .isEmpty();
  }

  private void done(Event e) {
    db.update(
        "UPDATE outbox SET state='DONE',owner=NULL,lease_until=0,last_error=NULL WHERE id=? AND"
            + " owner=?",
        e.id(),
        e.owner());
  }

  private void retry(Event e, String code) {
    int attempts =
        db.queryForObject("SELECT attempts FROM outbox WHERE id=?", Integer.class, e.id());
    long delay = Math.min(60000L, retryMs * (1L << Math.min(attempts - 1, 5)));
    db.update(
        "UPDATE outbox SET state='READY',owner=NULL,lease_until=0,available_at=?,last_error=? WHERE"
            + " id=? AND owner=?",
        System.currentTimeMillis() + delay,
        code,
        e.id(),
        e.owner());
  }
}
