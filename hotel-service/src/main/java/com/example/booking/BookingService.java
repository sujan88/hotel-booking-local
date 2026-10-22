package com.example.booking;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public BookingService(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) {
    this.db = db;
    this.tx = tx;
    this.json = json;
  }

  public ReservationView create(BookingRequest r, String key) {
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
      throw new ApiProblem(400, "INVALID_IDEMPOTENCY_KEY");
    String hash = Hashes.of(json, r);
    try {
      return tx.execute(
          t -> {
            // Serialize inventory decisions for this physical room, including concurrent
            // overlapping dates.
            var price =
                db.queryForList(
                    "SELECT nightly_cents FROM rooms WHERE hotel_id=? AND room_id=? FOR UPDATE",
                    Long.class,
                    r.hotelId(),
                    r.roomId());
            if (price.isEmpty()) throw new ApiProblem(404, "ROOM_NOT_FOUND");
            var previous =
                db.queryForList(
                    "SELECT id,status,request_hash FROM reservations WHERE idempotency_key=?", key);
            if (!previous.isEmpty()) {
              var old = previous.get(0);
              if (!hash.equals(old.get("REQUEST_HASH")))
                throw new ApiProblem(409, "IDEMPOTENCY_CONFLICT");
              return new ReservationView((String) old.get("ID"), (String) old.get("STATUS"));
            }
            int overlaps =
                db.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE hotel_id=? AND room_id=? AND held=TRUE"
                        + " AND check_in<? AND check_out>?",
                    Integer.class,
                    r.hotelId(),
                    r.roomId(),
                    r.checkOut(),
                    r.checkIn());
            if (overlaps > 0) throw new ApiProblem(409, "INVENTORY_UNAVAILABLE");
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            db.update(
                "INSERT INTO"
                    + " reservations(id,idempotency_key,request_hash,hotel_id,room_id,check_in,check_out,guests,guest_name,guest_email,payment_token,payment_key,amount_cents,status,held,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING_PAYMENT',TRUE,?,?)",
                id,
                key,
                hash,
                r.hotelId(),
                r.roomId(),
                r.checkIn(),
                r.checkOut(),
                r.guests(),
                r.guestName(),
                r.guestEmail(),
                r.paymentMethodToken(),
                "pay_" + id,
                Math.multiplyExact(
                    price.get(0), ChronoUnit.DAYS.between(r.checkIn(), r.checkOut())),
                now,
                now);
            enqueue(id, "PAYMENT_REQUESTED");
            return new ReservationView(id, "PENDING_PAYMENT");
          });
    } catch (DuplicateKeyException e) {
      throw new ApiProblem(409, "IDEMPOTENCY_CONFLICT");
    }
  }

  void enqueue(String id, String type) {
    db.update(
        "INSERT INTO outbox(id,reservation_id,event_type,state,available_at)"
            + " VALUES(?,?,?,'READY',?)",
        UUID.randomUUID().toString(),
        id,
        type,
        System.currentTimeMillis());
  }

  public ReservationView status(String id) {
    return db
        .query(
            "SELECT id,status FROM reservations WHERE id=?",
            (rs, n) -> new ReservationView(rs.getString(1), rs.getString(2)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiProblem(404, "RESERVATION_NOT_FOUND"));
  }

  public List<Map<String, Object>> availability(
      String hotel, LocalDate in, LocalDate out, int guests) {
    if (in == null
        || out == null
        || !out.isAfter(in)
        || ChronoUnit.DAYS.between(in, out) > 30
        || guests < 1
        || guests > 20) throw new ApiProblem(400, "INVALID_REQUEST");
    return db.query(
        "SELECT room_id,nightly_cents FROM rooms r WHERE hotel_id=? AND NOT EXISTS(SELECT 1 FROM"
            + " reservations b WHERE b.hotel_id=r.hotel_id AND b.room_id=r.room_id AND b.held=TRUE"
            + " AND b.check_in<? AND b.check_out>?) ORDER BY room_id",
        (rs, n) ->
            Map.<String, Object>of(
                "roomId",
                rs.getString(1),
                "roomType",
                "King",
                "price",
                java.math.BigDecimal.valueOf(rs.getLong(2), 2),
                "currency",
                "USD"),
        hotel,
        out,
        in);
  }
}
