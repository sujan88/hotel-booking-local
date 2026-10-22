package com.example.hotel.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.ai.model.chat=none",
      "spring.ai.openai.api-key=test-key",
      "resilience4j.retry.instances.reservation.waitDuration=10ms"
    })
@AutoConfigureMockMvc
class ReservationChatIntegrationTest {
  static final MockHotelProcess provider = MockHotelProcess.start();
  static final RestClient admin = RestClient.create(provider.baseUrl);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("hotel.api.base-url", () -> provider.baseUrl);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Models {
    @Bean
    ScriptedModel model() {
      return new ScriptedModel();
    }
  }

  static class ScriptedModel implements ChatModel {
    final AtomicInteger calls = new AtomicInteger();
    volatile String mode = "normal";

    @Override
    public ChatOptions getOptions() {
      return OpenAiChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      calls.incrementAndGet();
      var options = (ToolCallingChatOptions) prompt.getOptions();
      boolean available =
          options.getToolCallbacks().stream()
              .anyMatch(c -> c.getToolDefinition().name().equals("createReservation"));
      if (!available && !mode.equals("probe")) return done();
      if (available) {
        String schema =
            options.getToolCallbacks().stream()
                .filter(c -> c.getToolDefinition().name().equals("createReservation"))
                .findFirst()
                .orElseThrow()
                .getToolDefinition()
                .inputSchema();
        assertThat(schema).doesNotContain("idempotencyKey", "execution", "context");
      }
      boolean hadResult =
          prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
      if (hadResult && mode.equals("normal")) return done();
      String args = ReservationFingerprintTest.ARGS;
      if (hadResult)
        args =
            "{\"guestEmail\":\"alex@example.com\",\"guestName\":\" Alex   Guest"
                + " \",\"guests\":2.0,\"checkOut\":\"2027-01-02\",\"checkIn\":\"2027-01-01\",\"roomId\":\"r1\",\"hotelId\":\"h1\"}";
      if (mode.equals("distinct")) {
        long prior =
            prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
        args = ReservationFingerprintTest.ARGS.replace("r1", "r" + (prior + 1));
      }
      var call =
          new AssistantMessage.ToolCall(
              "call-" + calls.get(), "function", "createReservation", args);
      var batch =
          mode.equals("batch")
              ? List.of(
                  call,
                  new AssistantMessage.ToolCall("second", "function", "createReservation", args))
              : List.of(call);
      return response(
          AssistantMessage.builder().content("").toolCalls(batch).build(), "tool_calls");
    }

    static ChatResponse done() {
      return response(new AssistantMessage("Request processed."), "stop");
    }

    static ChatResponse response(AssistantMessage message, String finish) {
      return ChatResponse.builder()
          .generations(
              List.of(
                  new Generation(
                      message, ChatGenerationMetadata.builder().finishReason(finish).build())))
          .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build())
          .build();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ScriptedModel model;
  @Autowired CircuitBreakerRegistry circuits;
  @Autowired ObjectMapper mapper;
  String key;
  int before;

  @BeforeEach
  void reset() {
    model.calls.set(0);
    model.mode = "normal";
    key = UUID.randomUUID().toString();
    admin.post().uri("/admin/mode").body(Map.of("mode", "success")).retrieve().toBodilessEntity();
    circuits.circuitBreaker("reservation").reset();
    before = count();
  }

  @AfterAll
  static void stop() {
    provider.close();
  }

  static Map<?, ?> stats() {
    return admin.get().uri("/admin/reservations").retrieve().body(Map.class);
  }

  static int count() {
    return ((Number) stats().get("reservationCount")).intValue();
  }

  static int attempts() {
    return ((Number) stats().get("writeAttempts")).intValue();
  }

  String invoke(String operationKey) throws Exception {
    return mvc.perform(
            post("/api/chat")
                .header("Idempotency-Key", operationKey)
                .contentType("application/json")
                .content(
                    "{\"message\":\"Please reserve room r1 at h1 for Alex Guest, alex@example.com,"
                        + " Jan 1 to Jan 2 2027, two guests.\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void normalizedRepeatedWriteIsRejectedBeforeSecondBackendCall() throws Exception {
    model.mode = "duplicate";
    var result = mapper.readTree(invoke(key));
    assertThat(result.path("errorCode").asText()).isEqualTo("DUPLICATE_TOOL_CALL");
    assertThat(result.path("reservations").size()).isEqualTo(1);
    assertThat(result.path("reservations").get(0).path("reservationId").asText()).isNotBlank();
    assertThat(attempts()).isEqualTo(1);
    assertThat(count()).isEqualTo(before + 1);
    assertThat(model.calls.get()).isEqualTo(2);
  }

  @Test
  void duplicateWriteBatchExecutesNoBackendCalls() throws Exception {
    model.mode = "batch";
    assertThat(mapper.readTree(invoke(key)).path("errorCode").asText())
        .isEqualTo("DUPLICATE_TOOL_CALL");
    assertThat(attempts()).isZero();
    assertThat(count()).isEqualTo(before);
    assertThat(model.calls.get()).isEqualTo(1);
  }

  @Test
  void replayInAnotherExecutionIsAllowedButBackendDoesNotCreateAgain() throws Exception {
    assertThat(mapper.readTree(invoke(key)).path("success").asBoolean()).isTrue();
    assertThat(mapper.readTree(invoke(key)).path("success").asBoolean()).isTrue();
    assertThat(attempts()).isEqualTo(2);
    assertThat(count()).isEqualTo(before + 1);
  }

  @Test
  void concurrentExecutionsUseIndependentFingerprintSetsAndOneBackendSideEffect() throws Exception {
    var workers = Executors.newFixedThreadPool(2);
    try {
      var first = workers.submit(() -> invoke(key));
      var second = workers.submit(() -> invoke(key));
      assertThat(mapper.readTree(first.get(8, TimeUnit.SECONDS)).path("success").asBoolean())
          .isTrue();
      assertThat(mapper.readTree(second.get(8, TimeUnit.SECONDS)).path("success").asBoolean())
          .isTrue();
      assertThat(attempts()).isEqualTo(2);
      assertThat(count()).isEqualTo(before + 1);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void noKeyMeansNoWriteToolIsExposed() throws Exception {
    mvc.perform(
            post("/api/chat")
                .contentType("application/json")
                .content("{\"message\":\"Find rooms\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    assertThat(attempts()).isZero();
  }

  @Test
  void hallucinatedWriteWithoutAKeyCannotReachTheBackend() throws Exception {
    model.mode = "probe";
    mvc.perform(
            post("/api/chat")
                .contentType("application/json")
                .content("{\"message\":\"Book a room\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errorCode").value("INVALID_RESERVATION_REQUEST"));
    assertThat(attempts()).isZero();
  }

  @Test
  void serviceRetriesUseOneKeyAndFingerprintStillBlocksANewModelWrite() throws Exception {
    admin
        .post()
        .uri("/admin/mode")
        .body(Map.of("mode", "commit-then-fail"))
        .retrieve()
        .toBodilessEntity();
    model.mode = "duplicate";
    assertThat(mapper.readTree(invoke(key)).path("errorCode").asText())
        .isEqualTo("DUPLICATE_TOOL_CALL");
    assertThat(attempts()).isEqualTo(2);
    assertThat(count()).isEqualTo(before + 1);
    assertThat(model.calls.get()).isEqualTo(2);
  }

  @Test
  void nativePerToolLimitStillControlsDifferentWriteRequests() throws Exception {
    model.mode = "distinct";
    assertThat(mapper.readTree(invoke(key)).path("errorCode").asText())
        .isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
    assertThat(attempts()).isEqualTo(2);
    assertThat(count()).isEqualTo(before + 1);
    assertThat(model.calls.get()).isEqualTo(3);
  }

  @Test
  void invalidOperationKeyStopsBeforeTheModelOrBackend() throws Exception {
    assertThat(mapper.readTree(invoke("bad")).path("errorCode").asText())
        .isEqualTo("INVALID_IDEMPOTENCY_KEY");
    assertThat(attempts()).isZero();
    assertThat(model.calls.get()).isZero();
    assertThat(count()).isEqualTo(before);
  }
}
