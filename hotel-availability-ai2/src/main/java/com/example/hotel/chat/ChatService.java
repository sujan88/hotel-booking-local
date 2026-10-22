package com.example.hotel.chat;

import com.example.hotel.exception.DuplicateToolCallException;
import com.example.hotel.exception.InvalidReservationToolCallException;
import com.example.hotel.reservation.ReservationExecution;
import com.example.hotel.tool.ReservationTools;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private final ChatClient client;
  private final ReservationTools reservationTools;

  public ChatService(ChatClient client, ReservationTools reservationTools) {
    this.client = client;
    this.reservationTools = reservationTools;
  }

  public ChatResult chat(String message) {
    return chat(message, null);
  }

  public ChatResult chat(String message, String idempotencyKey) {
    return chat(message, idempotencyKey, null);
  }

  public ChatResult chat(String message, String idempotencyKey, String paymentMethodToken) {
    if (paymentMethodToken != null && idempotencyKey == null)
      return ChatResult.failure(
          "INVALID_IDEMPOTENCY_KEY", "Payment context requires an Idempotency-Key.");
    ReservationExecution execution;
    try {
      execution =
          idempotencyKey == null
              ? new ReservationExecution()
              : new ReservationExecution(idempotencyKey, paymentMethodToken);
    } catch (IllegalArgumentException e) {
      return ChatResult.failure("INVALID_IDEMPOTENCY_KEY", e.getMessage());
    }
    return execute(message, execution).withReservations(execution.results());
  }

  private ChatResult execute(String message, ReservationExecution execution) {
    try {
      // Spring AI owns the tool loop, conversation history, and tool-call counters.
      var prompt = client.prompt().user(message);
      if (execution.canCreateReservation()) prompt.tools(reservationTools);
      prompt.options(
          OpenAiChatOptions.builder()
              .toolContext(Map.of(ReservationExecution.CONTEXT_KEY, execution)));
      var response = prompt.call().chatResponse();
      if (response == null || response.getResults().size() != 1) return failed();
      var generation = response.getResult();
      if (ToolCallLimitExceededException.FINISH_REASON.equals(
          generation.getMetadata().getFinishReason())) {
        return ChatResult.failure("TOOL_CALL_LIMIT_EXCEEDED", generation.getOutput().getText());
      }
      String text = generation.getOutput().getText();
      if (!"stop".equalsIgnoreCase(generation.getMetadata().getFinishReason())
          || text == null
          || text.isBlank()) return failed();
      return ChatResult.success(text);
    } catch (DuplicateToolCallException e) {
      return ChatResult.failure(
          "DUPLICATE_TOOL_CALL",
          "An identical reservation was already attempted in this execution. No additional booking"
              + " request was sent. Reconcile or replay using the same Idempotency-Key.");
    } catch (InvalidReservationToolCallException e) {
      return ChatResult.failure("INVALID_RESERVATION_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      log.warn("Chat execution failed: cause={}", e.getClass().getSimpleName());
      return failed();
    }
  }

  private ChatResult failed() {
    return ChatResult.failure(
        "CHAT_EXECUTION_FAILED", "The assistant could not complete this request.");
  }
}
