package com.example.hotel.chat;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTest {
  @Test
  void serializesControlledFailure() throws Exception {
    var service = mock(ChatService.class);
    when(service.chat("Search"))
        .thenReturn(
            ChatResult.failure("TOOL_CALL_LIMIT_EXCEEDED", "The tool-call limit was reached."));
    MockMvcBuilders.standaloneSetup(new ChatController(service))
        .build()
        .perform(
            post("/api/chat").contentType("application/json").content("{\"message\":\"Search\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("TOOL_CALL_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.limitReason").doesNotExist());
  }

  @Test
  void rejectsBlankInputBeforeExecution() throws Exception {
    var service = mock(ChatService.class);
    MockMvcBuilders.standaloneSetup(new ChatController(service))
        .build()
        .perform(post("/api/chat").contentType("application/json").content("{\"message\":\" \"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
