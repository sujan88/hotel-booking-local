package com.example.hotel;

import static org.assertj.core.api.Assertions.*;

import com.example.hotel.dto.*;
import com.example.hotel.service.*;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-key",
      "resilience4j.retry.instances.availability.waitDuration=10ms",
      "resilience4j.timelimiter.instances.availability.timeoutDuration=300ms"
    })
class AvailabilityIntegrationTest {
  static final AtomicInteger calls = new AtomicInteger();
  static volatile String mode = "success";
  static final HttpServer server;
  static final ExecutorService workers = Executors.newCachedThreadPool();

  static {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(workers);
      server.createContext(
          "/hotels/",
          exchange -> {
            int attempt = calls.incrementAndGet();
            String current = mode;
            int status =
                current.equals("auth")
                    ? 401
                    : current.equals("limited")
                        ? 429
                        : current.equals("bad")
                            ? 400
                            : current.equals("fail") || (current.equals("retry") && attempt < 3)
                                ? 503
                                : 200;
            if (current.equals("slow"))
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            byte[] body =
                (status == 200
                        ? "[{\"roomId\":\"r1\",\"roomType\":\"King\",\"price\":120.00,\"currency\":\"USD\"}]"
                        : "{}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, body.length);
              exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) {
            } finally {
              exchange.close();
            }
          });
      server.start();
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("hotel.api.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Autowired AvailabilityService service;
  @Autowired CircuitBreakerRegistry circuits;
  final AvailabilityRequest request =
      new AvailabilityRequest("h1", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2), 2);

  @BeforeEach
  void reset() {
    calls.set(0);
    mode = "success";
    circuits.circuitBreaker("availability").reset();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
    workers.shutdownNow();
  }

  @Test
  void success() {
    assertThat(service.search(request).rooms()).hasSize(1);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void retriesTransientErrorsThenSucceeds() {
    mode = "retry";
    assertThat(service.search(request).success()).isTrue();
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void timeoutIsStructuredAfterRetries() {
    mode = "slow";
    long start = System.nanoTime();
    var result = service.search(request);
    assertThat(result.errorCode()).isEqualTo("TIMEOUT");
    assertThat(result.retryable()).isTrue();
    assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void openCircuitNeverCallsHttp() {
    circuits.circuitBreaker("availability").transitionToOpenState();
    assertThat(service.search(request).errorCode()).isEqualTo("CIRCUIT_OPEN");
    assertThat(calls.get()).isZero();
  }

  @Test
  void badRequestIsNotRetriedOrCountedAsFailure() {
    mode = "bad";
    var result = service.search(request);
    assertThat(result.errorCode()).isEqualTo("INVALID_REQUEST");
    assertThat(result.retryable()).isFalse();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(circuits.circuitBreaker("availability").getMetrics().getNumberOfFailedCalls())
        .isZero();
  }

  @Test
  void authenticationFailureIsNotRetried() {
    mode = "auth";
    assertThat(service.search(request).errorCode()).isEqualTo("UPSTREAM_ERROR");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void rateLimitIsRetried() {
    mode = "limited";
    assertThat(service.search(request).errorCode()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void repeatedFailuresOpenCircuit() {
    mode = "fail";
    for (int i = 0; i < 10; i++) service.search(request);
    assertThat(circuits.circuitBreaker("availability").getState())
        .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    int before = calls.get();
    assertThat(service.search(request).errorCode()).isEqualTo("CIRCUIT_OPEN");
    assertThat(calls.get()).isEqualTo(before);
  }

  @Test
  void exhaustedRetriesAreOneCircuitFailure() {
    mode = "fail";
    assertThat(service.search(request).errorCode()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    assertThat(calls.get()).isEqualTo(3);
    assertThat(circuits.circuitBreaker("availability").getMetrics().getNumberOfFailedCalls())
        .isEqualTo(1);
  }
}
