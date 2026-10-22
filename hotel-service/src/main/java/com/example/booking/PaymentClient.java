package com.example.booking;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.*;

@Component
public class PaymentClient {
  public record PaymentRequest(
      String paymentKey, String paymentMethodToken, long amountCents, String currency) {}

  public record PaymentResult(String status) {}

  private final RestClient client;
  private final String base;
  private final WebServerApplicationContext context;

  public PaymentClient(
      @Value("${payment.base-url:}") String base,
      @Value("${payment.timeout-ms:500}") long timeout,
      WebServerApplicationContext context) {
    this.base = base;
    this.context = context;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).build());
    factory.setReadTimeout(Duration.ofMillis(timeout));
    client = RestClient.builder().requestFactory(factory).build();
  }

  private String url() {
    return base.isBlank()
        ? "http://127.0.0.1:" + context.getWebServer().getPort() + "/simulator"
        : base;
  }

  public String resolve(PaymentRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Payment HTTP must be outside a database transaction");
    try {
      PaymentResult result;
      try {
        result =
            client
                .get()
                .uri(url() + "/payments/" + request.paymentKey())
                .retrieve()
                .body(PaymentResult.class);
      } catch (HttpClientErrorException.NotFound e) {
        result =
            client
                .post()
                .uri(url() + "/payments")
                .header("Idempotency-Key", request.paymentKey())
                .body(request)
                .retrieve()
                .body(PaymentResult.class);
      }
      if (result == null
          || !java.util.Set.of("SUCCEEDED", "DECLINED", "PENDING").contains(result.status()))
        return "UNKNOWN";
      return result.status();
    } catch (RestClientException e) {
      return "UNKNOWN";
    }
  }
}
