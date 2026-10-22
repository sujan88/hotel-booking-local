package com.example.hotel.reservation;

import com.example.hotel.exception.InvalidReservationToolCallException;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Orchestration boundary: reject identical WRITE calls before the tool or backend runs. */
public final class WriteFingerprintingToolCallingManager implements ToolCallingManager {
  private final ToolCallingManager delegate;
  private final ReservationFingerprint fingerprints;

  public WriteFingerprintingToolCallingManager(
      ToolCallingManager delegate, ReservationFingerprint fingerprints) {
    this.delegate = delegate;
    this.fingerprints = fingerprints;
  }

  @Override
  public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
    return delegate.resolveToolDefinitions(options);
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
    var writes =
        response.getResult().getOutput().getToolCalls().stream()
            .filter(c -> c.name().equals("createReservation"))
            .toList();
    if (!writes.isEmpty()) {
      if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
          || options.getToolContext() == null
          || !(options.getToolContext().get(ReservationExecution.CONTEXT_KEY)
              instanceof ReservationExecution execution)
          || !execution.canCreateReservation()) throw new InvalidReservationToolCallException();
      execution.claimBatch(writes.stream().map(fingerprints::of).toList());
    }
    // Native manager still owns total/per-tool counts, tool dispatch and history.
    return delegate.executeToolCalls(prompt, response);
  }
}
