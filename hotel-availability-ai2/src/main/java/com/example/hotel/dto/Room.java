package com.example.hotel.dto;

import java.math.BigDecimal;

public record Room(String roomId, String roomType, BigDecimal price, String currency) {}
