package com.example.hotel.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class ResilienceLogging {
  private static final Logger log = LoggerFactory.getLogger(ResilienceLogging.class);

  public ResilienceLogging(CircuitBreakerRegistry circuits, RetryRegistry retries) {
    circuits
        .circuitBreaker("availability")
        .getEventPublisher()
        .onStateTransition(e -> log.info("Availability circuit: {}", e.getStateTransition()));
    retries
        .retry("availability")
        .getEventPublisher()
        .onRetry(e -> log.info("Availability retry attempt={}", e.getNumberOfRetryAttempts()));
  }
}
