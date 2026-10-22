package com.example.hotel.reservation;

import com.example.hotel.dto.ReservationRequest;
import com.example.hotel.exception.InvalidReservationToolCallException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;

@Component
public class ReservationFingerprint {
  private final ObjectMapper mapper;

  public ReservationFingerprint(ObjectMapper mapper) {
    this.mapper =
        mapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
  }

  public String of(ToolCall call) {
    try {
      var node = mapper.readTree(call.arguments());
      if (node == null || !node.isObject() || node.size() != 7)
        throw new IllegalArgumentException();
      for (var field :
          List.of("hotelId", "roomId", "checkIn", "checkOut", "guestName", "guestEmail"))
        if (!node.path(field).isTextual()) throw new IllegalArgumentException();
      if (!node.path("guests").isNumber()) throw new IllegalArgumentException();
      var r =
          new ReservationRequest(
              node.get("hotelId").textValue(),
              node.get("roomId").textValue(),
              LocalDate.parse(node.get("checkIn").textValue()),
              LocalDate.parse(node.get("checkOut").textValue()),
              node.get("guests").decimalValue().intValueExact(),
              node.get("guestName").textValue(),
              node.get("guestEmail").textValue());
      var args = new TreeMap<String, Object>();
      args.put("hotelId", r.hotelId());
      args.put("roomId", r.roomId());
      args.put("checkIn", r.checkIn().toString());
      args.put("checkOut", r.checkOut().toString());
      args.put("guests", r.guests());
      args.put("guestName", r.guestName());
      args.put("guestEmail", r.guestEmail());
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(call.name().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      return HexFormat.of().formatHex(digest.digest(mapper.writeValueAsBytes(args)));
    } catch (Exception e) {
      throw new InvalidReservationToolCallException();
    }
  }
}
