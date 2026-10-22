package com.example.hotel.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Real OpenAI HTTP adapter, real tool callback and real hotel HTTP adapter, all local. */
@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key"})
@AutoConfigureMockMvc
class ChatHttpIntegrationTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger models = new AtomicInteger();
  static final AtomicInteger hotels = new AtomicInteger();
  static volatile boolean repeat;
  static final HttpServer server;

  static {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            var request = JSON.readTree(exchange.getRequestBody());
            int count = models.incrementAndGet();
            Map<String, Object> message;
            if (count == 1 || repeat) {
              String args =
                  "{\"hotelId\":\"h1\",\"checkIn\":\"2027-01-01\",\"checkOut\":\"2027-01-02\",\"guests\":2}";
              message =
                  Map.of(
                      "role",
                      "assistant",
                      "content",
                      "",
                      "tool_calls",
                      List.of(
                          Map.of(
                              "id",
                              "call-" + count,
                              "type",
                              "function",
                              "function",
                              Map.of("name", "searchAvailability", "arguments", args))));
            } else {
              assertThat(request.path("messages").toString()).contains("roomId", "r1");
              message =
                  Map.of("role", "assistant", "content", "A King room is available for USD 120.");
            }
            byte[] body =
                JSON.writeValueAsBytes(
                    Map.of(
                        "id",
                        "chat-local",
                        "object",
                        "chat.completion",
                        "created",
                        1,
                        "model",
                        "gpt-4o-mini",
                        "choices",
                        List.of(
                            Map.of(
                                "index",
                                0,
                                "message",
                                message,
                                "finish_reason",
                                count == 1 || repeat ? "tool_calls" : "stop")),
                        "usage",
                        Map.of(
                            "prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.createContext(
          "/hotels/",
          exchange -> {
            hotels.incrementAndGet();
            byte[] body =
                "[{\"roomId\":\"r1\",\"roomType\":\"King\",\"price\":120,\"currency\":\"USD\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.ai.openai.base-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    registry.add("hotel.api.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    models.set(0);
    hotels.set(0);
    repeat = false;
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @Test
  void successRunsExactlyOneHotelSearch() throws Exception {
    mvc.perform(
            post("/api/chat")
                .contentType("application/json")
                .content("{\"message\":\"Search h1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("A King room is available for USD 120."));
    assertThat(models.get()).isEqualTo(2);
    assertThat(hotels.get()).isEqualTo(1);
  }

  @Test
  void loopingModelStopsAtTheNativeLimit() throws Exception {
    repeat = true;
    mvc.perform(
            post("/api/chat")
                .contentType("application/json")
                .content("{\"message\":\"Search h1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("TOOL_CALL_LIMIT_EXCEEDED"));
    assertThat(models.get()).isEqualTo(3);
    assertThat(hotels.get()).isEqualTo(2);
  }
}
