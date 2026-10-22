package com.example.hotel.config;

import com.example.hotel.reservation.ReservationFingerprint;
import com.example.hotel.reservation.WriteFingerprintingToolCallingManager;
import com.example.hotel.tool.HotelTools;
import com.example.hotel.tool.ReservationStatusTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
  @Bean
  public ChatClient chatClient(
      ChatClient.Builder builder,
      ToolCallingManager toolCallingManager,
      HotelTools tools,
      ReservationStatusTools statusTools,
      ReservationFingerprint fingerprints) {
    // Use Spring AI's auto-configured manager so application.yml native limits apply.
    var toolAdvisor =
        ToolCallingAdvisor.builder()
            .toolCallingManager(
                new WriteFingerprintingToolCallingManager(toolCallingManager, fingerprints))
            .build();
    return builder
        .defaultSystem(
            "You help guests search hotels. Ask for missing hotel ID, dates and guest count. Use"
                + " the availability tool for live results. Never invent inventory or prices."
                + " Explain tool failures without treating them as no rooms. Book only when"
                + " explicitly requested with complete guest details. Never claim a reservation"
                + " failed when its outcome is unknown; reconcile with the same booking operation."
                + " If payment is required, direct the guest to secure checkout. Never ask for card"
                + " details or payment tokens in chat. Tokens arrive through backend context. A"
                + " pending reservation is not confirmed. Include the reservation ID and actual"
                + " status in booking replies. Use getReservationStatus for an existing booking;"
                + " avoid polling repeatedly within one chat request.")
        .defaultTools(tools, statusTools)
        .defaultAdvisors(toolAdvisor)
        .build();
  }
}
