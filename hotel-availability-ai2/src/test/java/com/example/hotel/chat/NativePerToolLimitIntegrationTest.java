package com.example.hotel.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotel.dto.AvailabilityResponse;
import com.example.hotel.service.AvailabilityService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "spring.ai.model.chat=none",
      "spring.ai.openai.api-key=test-key",
      "spring.ai.tools.limits.max-total-tool-calls=5",
      "spring.ai.tools.limits.max-calls-per-tool-default=1"
    })
class NativePerToolLimitIntegrationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class Models {
    @Bean
    LoopingModel testModel() {
      return new LoopingModel();
    }
  }

  static class LoopingModel implements ChatModel {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatOptions getOptions() {
      return OpenAiChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      int n = calls.incrementAndGet();
      // Distinct searches also count toward the native per-turn limit.
      String args =
          "{\"hotelId\":\"h"
              + n
              + "\",\"checkIn\":\"2027-01-01\",\"checkOut\":\"2027-01-02\",\"guests\":2}";
      return ChatServiceTest.tool(ChatServiceTest.call("call-" + n, args));
    }
  }

  @Autowired ChatService service;
  @Autowired LoopingModel model;
  @Autowired ToolCallingManager manager;
  @Autowired ToolCallingProperties properties;
  @MockitoBean AvailabilityService availability;

  @Test
  void nativeConfigurationStopsLoopAndResetsForANewUserRequest() {
    assertThat(manager).isInstanceOf(DefaultToolCallingManager.class);
    assertThat(properties.getLimits().getMaxTotalToolCalls()).isEqualTo(5);
    assertThat(properties.getLimits().getMaxCallsPerToolDefault()).isEqualTo(1);
    when(availability.search(any())).thenReturn(AvailabilityResponse.success(List.of()));
    for (int request = 0; request < 2; request++) {
      var result = service.chat("Search hotels");
      assertThat(result.errorCode()).isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
    }
    assertThat(model.calls.get()).isEqualTo(4);
    verify(availability, times(2)).search(any());
  }
}
