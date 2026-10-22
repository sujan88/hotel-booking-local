package com.example.hotel.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.chat.ChatService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.ai.model.chat=none", "spring.ai.openai.api-key=test-key"})
@AutoConfigureMockMvc
class PaymentFlowIntegrationTest {
  static final String TOKEN = "pm_private_simulated_token";
  static final AtomicReference<String> sent = new AtomicReference<>();
  static final HttpServer provider;

  static {
    try {
      provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext(
          "/reservations",
          exchange -> {
            boolean write = exchange.getRequestMethod().equals("POST");
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String response;
            if (write) {
              sent.set(body);
              if (!body.contains(TOKEN)) {
                status = 402;
                response = "{\"errorCode\":\"PAYMENT_REQUIRED\"}";
              } else {
                status = 202;
                response = "{\"reservationId\":\"res-123\",\"status\":\"PENDING_PAYMENT\"}";
              }
            } else response = "{\"reservationId\":\"res-123\",\"status\":\"CONFIRMED\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      provider.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("hotel.api.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    provider.stop(0);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Models {
    @Bean
    ScriptedModel model() {
      return new ScriptedModel();
    }
  }

  static class ScriptedModel implements ChatModel {
    volatile String mode = "create";
    volatile String toolResult;

    @Override
    public ChatOptions getOptions() {
      return OpenAiChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      var options = (ToolCallingChatOptions) prompt.getOptions();
      for (var callback : options.getToolCallbacks())
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("paymentMethodToken");
      assertThat(prompt.getInstructions().toString()).doesNotContain(TOKEN);
      var results =
          prompt.getInstructions().stream()
              .filter(ToolResponseMessage.class::isInstance)
              .map(ToolResponseMessage.class::cast)
              .toList();
      if (!results.isEmpty()) {
        toolResult = results.toString();
        if (mode.equals("reply-failure"))
          throw new IllegalStateException("Simulated final model failure");
        assertThat(toolResult).doesNotContain(TOKEN);
        return response(
            AssistantMessage.builder().content("Processed reservation status.").build(), "stop");
      }
      String name = mode.equals("status") ? "getReservationStatus" : "createReservation";
      String args =
          mode.equals("status")
              ? "{\"reservationId\":\"res-123\"}"
              : ReservationFingerprintTest.ARGS;
      return response(
          AssistantMessage.builder()
              .content("")
              .toolCalls(
                  List.of(new AssistantMessage.ToolCall("payment-call", "function", name, args)))
              .build(),
          "tool_calls");
    }

    static ChatResponse response(AssistantMessage message, String finish) {
      return new ChatResponse(
          List.of(
              new Generation(
                  message, ChatGenerationMetadata.builder().finishReason(finish).build())));
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ChatService chat;
  @Autowired ScriptedModel model;

  @BeforeEach
  void reset() {
    sent.set(null);
    model.mode = "create";
    model.toolResult = null;
  }

  @Test
  void secureContextTokenReachesBackendAndPendingStatusReachesModel() throws Exception {
    mvc.perform(
            post("/api/chat")
                .header("Idempotency-Key", "booking-secure-001")
                .header("Payment-Method-Token", TOKEN)
                .contentType("application/json")
                .content("{\"message\":\"Book hotel h1 room r1 for Alex Guest.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.reservations[0].reservationId").value("res-123"))
        .andExpect(jsonPath("$.reservations[0].status").value("PENDING_PAYMENT"));
    assertThat(sent.get()).contains(TOKEN);
    assertThat(model.toolResult)
        .contains("PENDING_PAYMENT")
        .doesNotContain("Reservation confirmed.");
  }

  @Test
  void missingTokenProducesPaymentRequiredToolResponse() {
    var result = chat.chat("Book this hotel", "booking-missing-token");
    assertThat(result.success()).isTrue();
    assertThat(result.reservations())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.reservationId()).isNull();
              assertThat(r.errorCode()).isEqualTo("PAYMENT_REQUIRED");
            });
    assertThat(model.toolResult).contains("PAYMENT_REQUIRED");
  }

  @Test
  void statusToolIsAvailableWithoutWriteKey() {
    model.mode = "status";
    var result = chat.chat("Check reservation res-123");
    assertThat(result.success()).isTrue();
    assertThat(result.reservations())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.reservationId()).isEqualTo("res-123");
              assertThat(r.status()).isEqualTo("CONFIRMED");
            });
    assertThat(model.toolResult).contains("CONFIRMED");
    assertThat(sent.get()).isNull();
  }

  @Test
  void directApiReturns202AndStatusCanBeRead() throws Exception {
    String args = ReservationFingerprintTest.ARGS;
    String body =
        args.substring(0, args.length() - 1) + ",\"paymentMethodToken\":\"" + TOKEN + "\"}";
    mvc.perform(
            post("/api/reservations")
                .header("Idempotency-Key", "booking-http-payment")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    mvc.perform(get("/api/reservations/res-123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void successfulBookingIdSurvivesFinalModelFailure() {
    model.mode = "reply-failure";
    var result = chat.chat("Book this hotel", "booking-model-failure", TOKEN);
    assertThat(result.success()).isFalse();
    assertThat(result.errorCode()).isEqualTo("CHAT_EXECUTION_FAILED");
    assertThat(result.reservations())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.reservationId()).isEqualTo("res-123");
              assertThat(r.status()).isEqualTo("PENDING_PAYMENT");
            });
  }

  @Test
  void toolResultsDoNotLeakIntoNextChatRequest() {
    assertThat(chat.chat("Book this hotel", "booking-isolation-001", TOKEN).reservations())
        .hasSize(1);
    assertThat(chat.chat("Book this hotel", "invalid").reservations()).isEmpty();
  }
}
