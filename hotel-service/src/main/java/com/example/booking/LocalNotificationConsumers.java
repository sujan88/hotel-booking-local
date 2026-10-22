package com.example.booking;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Local substitute for two SQS queues, with an independent transaction per consumer. */
@Component
public class LocalNotificationConsumers {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public LocalNotificationConsumers(
      JdbcTemplate db,
      TransactionTemplate tx,
      @Value("${saga.worker-enabled:true}") boolean enabled) {
    this.db = db;
    this.tx = tx;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${saga.poll-ms:1000}")
  public void poll() {
    if (enabled) runOnce();
  }

  public void runOnce() {
    for (String channel : List.of("EMAIL", "PMS")) consume(channel);
  }

  public void consume(String channel) {
    if (!List.of("EMAIL", "PMS").contains(channel))
      throw new IllegalArgumentException("Unknown consumer");
    for (int i = 0; i < 10; i++) {
      Boolean processed =
          tx.execute(
              t -> {
                var deliveries =
                    db.queryForList(
                        "SELECT event_id,reservation_id FROM notification_deliveries WHERE"
                            + " channel=? AND state='READY' ORDER BY event_id FETCH FIRST 1 ROW"
                            + " ONLY FOR UPDATE",
                        channel);
                if (deliveries.isEmpty()) return false;
                var delivery = deliveries.get(0);
                String event = (String) delivery.get("EVENT_ID"),
                    reservation = (String) delivery.get("RESERVATION_ID");
                // The row represents a SIMULATED email/PMS effect. No external network call here.
                db.update(
                    "INSERT INTO notification_inbox(event_id,channel,reservation_id,processed_at)"
                        + " SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM notification_inbox WHERE"
                        + " event_id=? AND channel=?)",
                    event,
                    channel,
                    reservation,
                    System.currentTimeMillis(),
                    event,
                    channel);
                db.update(
                    "UPDATE notification_deliveries SET state='DONE' WHERE event_id=? AND"
                        + " channel=?",
                    event,
                    channel);
                return true;
              });
      if (!Boolean.TRUE.equals(processed)) return;
    }
  }
}
