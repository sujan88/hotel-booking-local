package com.example.hotel.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }

  public record Request(@NotBlank @Size(max = 4000) String message) {}

  @PostMapping
  public ChatResult chat(
      @Valid @RequestBody Request request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "Payment-Method-Token", required = false) String paymentMethodToken) {
    if (paymentMethodToken != null)
      return service.chat(request.message(), idempotencyKey, paymentMethodToken);
    return idempotencyKey == null
        ? service.chat(request.message())
        : service.chat(request.message(), idempotencyKey);
  }
}
