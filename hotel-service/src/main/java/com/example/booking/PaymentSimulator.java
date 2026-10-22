package com.example.booking;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/simulator")
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true")
public class PaymentSimulator {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public PaymentSimulator(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) {
    this.db = db;
    this.tx = tx;
    this.json = json;
  }

  @PostMapping("/payment-methods")
  public Map<String, String> collect() {
    String token = "pm_" + UUID.randomUUID();
    db.update("INSERT INTO payment_methods(token) VALUES(?)", token);
    return Map.of("paymentMethodToken", token, "warning", "SIMULATION ONLY: no money collected");
  }

  @PostMapping("/mode")
  public Map<String, String> mode(@RequestBody Map<String, String> body) {
    String mode = body.getOrDefault("mode", "");
    if (!Set.of("SUCCESS", "DECLINE", "PENDING", "TIMEOUT_BEFORE_COMMIT", "COMMIT_THEN_TIMEOUT")
        .contains(mode)) throw new ApiProblem(400, "INVALID_MODE");
    db.update("UPDATE simulator_settings SET mode=? WHERE id=1", mode);
    return Map.of("mode", mode);
  }

  @GetMapping("/payments/{key}")
  public PaymentClient.PaymentResult query(@PathVariable String key) {
    return db
        .query(
            "SELECT status FROM simulated_payments WHERE payment_key=?",
            (rs, n) -> new PaymentClient.PaymentResult(rs.getString(1)),
            key)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiProblem(404, "PAYMENT_NOT_FOUND"));
  }

  @PostMapping("/payments")
  public PaymentClient.PaymentResult pay(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody PaymentClient.PaymentRequest request) {
    if (key == null
        || !key.equals(request.paymentKey())
        || !key.matches("pay_[a-f0-9-]{36}")
        || request.amountCents() <= 0
        || !"USD".equals(request.currency())) throw new ApiProblem(400, "INVALID_PAYMENT_REQUEST");
    String mode = db.queryForObject("SELECT mode FROM simulator_settings WHERE id=1", String.class);
    String hash = Hashes.of(json, request);
    // Serialize provider decisions separately from the booking transaction.
    var result =
        tx.execute(
            t -> {
              db.queryForObject(
                  "SELECT mode FROM simulator_settings WHERE id=1 FOR UPDATE", String.class);
              var old =
                  db.queryForList(
                      "SELECT status,request_hash FROM simulated_payments WHERE payment_key=?",
                      key);
              if (!old.isEmpty()) {
                if (!hash.equals(old.get(0).get("REQUEST_HASH")))
                  throw new ApiProblem(409, "PAYMENT_KEY_CONFLICT");
                return new PaymentClient.PaymentResult((String) old.get(0).get("STATUS"));
              }
              if (db.queryForObject(
                      "SELECT COUNT(*) FROM payment_methods WHERE token=?",
                      Integer.class,
                      request.paymentMethodToken())
                  != 1) throw new ApiProblem(400, "INVALID_PAYMENT_TOKEN");
              if ("TIMEOUT_BEFORE_COMMIT".equals(mode)) return null;
              String state =
                  "DECLINE".equals(mode)
                      ? "DECLINED"
                      : "PENDING".equals(mode) ? "PENDING" : "SUCCEEDED";
              db.update(
                  "INSERT INTO simulated_payments(payment_key,request_hash,status,amount_cents)"
                      + " VALUES(?,?,?,?)",
                  key,
                  hash,
                  state,
                  request.amountCents());
              return new PaymentClient.PaymentResult(state);
            });
    if (result == null || "COMMIT_THEN_TIMEOUT".equals(mode)) {
      try {
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (result == null) throw new ApiProblem(504, "SIMULATED_TIMEOUT");
    }
    return result;
  }

  @PostMapping("/payments/{key}/resolve")
  public PaymentClient.PaymentResult resolve(
      @PathVariable String key, @RequestBody Map<String, String> body) {
    String status = body.get("status");
    if (!Set.of("SUCCEEDED", "DECLINED").contains(status == null ? "" : status))
      throw new ApiProblem(400, "INVALID_STATUS");
    int changed =
        db.update(
            "UPDATE simulated_payments SET status=? WHERE payment_key=? AND status='PENDING'",
            status,
            key);
    if (changed == 0) throw new ApiProblem(409, "PAYMENT_NOT_PENDING");
    return query(key);
  }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return Map.of(
        "reservations", db.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class),
        "successfulPayments",
            db.queryForObject(
                "SELECT COUNT(*) FROM simulated_payments WHERE status='SUCCEEDED'", Integer.class),
        "heldReservations",
            db.queryForObject("SELECT COUNT(*) FROM reservations WHERE held=TRUE", Integer.class),
        "notifications",
            db.queryForObject("SELECT COUNT(*) FROM notification_inbox", Integer.class),
        "outbox",
            db.queryForList(
                "SELECT event_type,state,attempts,last_error FROM outbox ORDER BY event_type"));
  }
}
