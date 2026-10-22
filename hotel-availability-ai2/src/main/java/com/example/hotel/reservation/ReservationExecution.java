package com.example.hotel.reservation;

import com.example.hotel.dto.ReservationResponse;
import com.example.hotel.exception.DuplicateToolCallException;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;

/** One instance per chat request. No global duplicate set and no thread-local state. */
public final class ReservationExecution {
  public static final String CONTEXT_KEY = "hotel.reservationExecution";
  private final String idempotencyKey;
  private final String paymentMethodToken;
  private final Set<String> executedToolCalls = new HashSet<>();
  private final List<ReservationResponse> results = new ArrayList<>();

  /** Read-only chat: status results can be collected, but no booking key is available. */
  public ReservationExecution() {
    this.idempotencyKey = null;
    this.paymentMethodToken = null;
  }

  public ReservationExecution(String key) {
    this(key, null);
  }

  public ReservationExecution(String key, String paymentMethodToken) {
    if (paymentMethodToken != null && !paymentMethodToken.matches("pm_[A-Za-z0-9_-]{8,90}"))
      throw new IllegalArgumentException("Invalid payment token");
    this.paymentMethodToken = paymentMethodToken;
    this.idempotencyKey = IdempotencyKey.requireValid(key);
  }

  public String paymentMethodToken() {
    return paymentMethodToken;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public boolean canCreateReservation() {
    return idempotencyKey != null;
  }

  /** Expose service results separately from the model's natural-language answer. */
  public static ReservationResponse capture(ToolContext context, ReservationResponse response) {
    if (context != null
        && context.getContext().get(CONTEXT_KEY) instanceof ReservationExecution execution)
      execution.addResult(response);
    return response;
  }

  private synchronized void addResult(ReservationResponse response) {
    results.add(response);
  }

  public synchronized List<ReservationResponse> results() {
    return List.copyOf(results);
  }

  /** Claim before dispatch, including uncertain/failed writes; service retries own recovery. */
  public synchronized void claimBatch(List<String> fingerprints) {
    var pending = new HashSet<>(executedToolCalls);
    for (String fingerprint : fingerprints)
      if (!pending.add(fingerprint)) throw new DuplicateToolCallException();
    executedToolCalls.clear();
    executedToolCalls.addAll(pending);
  }
}
