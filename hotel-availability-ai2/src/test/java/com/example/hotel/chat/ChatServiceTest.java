package com.example.hotel.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.hotel.config.ChatConfig;
import com.example.hotel.dto.AvailabilityResponse;
import com.example.hotel.service.AvailabilityService;
import com.example.hotel.tool.HotelTools;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.openai.OpenAiChatOptions;

class ChatServiceTest {
  private final ChatModel model = mock(ChatModel.class);
  private final AvailabilityService availability = mock(AvailabilityService.class);
  private static final String ARGS =
      "{\"hotelId\":\"h1\",\"checkIn\":\"2027-01-01\",\"checkOut\":\"2027-01-02\",\"guests\":2}";

  @BeforeEach
  void setup() {
    when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
    when(availability.search(any())).thenReturn(AvailabilityResponse.success(List.of()));
  }

  private ChatService service(int total, int perTool) {
    var manager =
        ToolCallingManager.builder()
            .maxTotalToolCalls(total)
            .maxCallsPerTool(perTool)
            .onLimitExceeded(ToolCallLimitBehavior.THROW)
            .build();
    var client =
        new ChatConfig()
            .chatClient(
                ChatClient.builder(model),
                manager,
                new HotelTools(availability),
                new com.example.hotel.tool.ReservationStatusTools(
                    mock(com.example.hotel.service.ReservationService.class)),
                new com.example.hotel.reservation.ReservationFingerprint(
                    new tools.jackson.databind.ObjectMapper()));
    return new ChatService(
        client,
        new com.example.hotel.tool.ReservationTools(
            mock(com.example.hotel.service.ReservationService.class)));
  }

  static AssistantMessage.ToolCall call(String id, String args) {
    return new AssistantMessage.ToolCall(id, "function", "searchAvailability", args);
  }

  static ChatResponse tool(AssistantMessage.ToolCall... calls) {
    return response(
        AssistantMessage.builder().content("").toolCalls(List.of(calls)).build(), "tool_calls");
  }

  static ChatResponse done() {
    return response(new AssistantMessage("No rooms are currently available."), "stop");
  }

  static ChatResponse response(AssistantMessage output, String finish) {
    return ChatResponse.builder()
        .generations(
            List.of(
                new Generation(
                    output, ChatGenerationMetadata.builder().finishReason(finish).build())))
        .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build())
        .build();
  }

  @Test
  void nativeLoopExecutesToolAndSendsResultBackToModel() {
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Prompt prompt = invocation.getArgument(0);
              return prompt.getInstructions().stream()
                      .anyMatch(ToolResponseMessage.class::isInstance)
                  ? done()
                  : tool(call("1", ARGS));
            });
    assertThat(service(3, 2).chat("Search h1").success()).isTrue();
    verify(model, times(2)).call(any(Prompt.class));
    verify(availability, times(1)).search(any());
  }

  @Test
  void identicalCallsAreAllowedUntilNativePerToolLimitIsReached() {
    when(model.call(any(Prompt.class)))
        .thenReturn(tool(call("1", ARGS)), tool(call("2", ARGS)), tool(call("3", ARGS)));
    var result = service(3, 2).chat("Search h1");
    assertThat(result.success()).isFalse();
    assertThat(result.errorCode()).isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
    verify(model, times(3)).call(any(Prompt.class));
    verify(availability, times(2)).search(any());
  }

  @Test
  void nativeTotalLimitStopsBatchAfterFirstAdmittedCall() {
    when(model.call(any(Prompt.class)))
        .thenReturn(tool(call("1", ARGS), call("2", ARGS.replace("h1", "h2"))));
    var result = service(1, 5).chat("Search two hotels");
    assertThat(result.errorCode()).isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
    verify(model, times(1)).call(any(Prompt.class));
    verify(availability, times(1)).search(any());
  }

  @Test
  void preservesOriginalBatchAndBothResults() {
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Prompt prompt = invocation.getArgument(0);
              var results =
                  prompt.getInstructions().stream()
                      .filter(ToolResponseMessage.class::isInstance)
                      .map(ToolResponseMessage.class::cast)
                      .toList();
              if (results.isEmpty())
                return tool(call("1", ARGS), call("2", ARGS.replace("h1", "h2")));
              assertThat(results).hasSize(1);
              assertThat(results.get(0).getResponses()).hasSize(2);
              return done();
            });
    assertThat(service(3, 2).chat("Search two hotels").success()).isTrue();
    verify(availability, times(2)).search(any());
  }

  @Test
  void providerFailureReturnsSanitizedResponse() {
    when(model.call(any(Prompt.class)))
        .thenThrow(new IllegalStateException("private provider details"));
    var result = service(3, 2).chat("Search");
    assertThat(result.errorCode()).isEqualTo("CHAT_EXECUTION_FAILED");
    assertThat(result.message()).doesNotContain("private provider details");
  }

  @Test
  void missingUsageDoesNotRequireACustomTokenBudget() {
    var generation =
        new Generation(
            new AssistantMessage("Hello"),
            ChatGenerationMetadata.builder().finishReason("stop").build());
    when(model.call(any(Prompt.class)))
        .thenReturn(ChatResponse.builder().generations(List.of(generation)).build());
    assertThat(service(3, 2).chat("Hello").success()).isTrue();
  }

  @Test
  void truncatedAnswerIsNotReportedAsSuccessful() {
    when(model.call(any(Prompt.class)))
        .thenReturn(response(new AssistantMessage("partial"), "length"));
    assertThat(service(3, 2).chat("Search").success()).isFalse();
  }
}
