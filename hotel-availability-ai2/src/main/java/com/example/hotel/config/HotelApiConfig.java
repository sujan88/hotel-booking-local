package com.example.hotel.config;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
public class HotelApiConfig {
  @Bean
  public ThreadPoolTaskExecutor availabilityExecutor() {
    return executor("availability-", 8, 16, 16);
  }

  @Bean
  public ThreadPoolTaskExecutor reservationExecutor() {
    return executor("reservation-", 4, 8, 8);
  }

  private ThreadPoolTaskExecutor executor(String name, int coreSize, int maxSize, int queueSize) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(coreSize);
    executor.setMaxPoolSize(maxSize);
    executor.setQueueCapacity(queueSize);
    executor.setThreadNamePrefix(name);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  @Bean
  public RestClient hotelRestClient(
      RestClient.Builder builder, @Value("${hotel.api.base-url}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(500));
    factory.setReadTimeout(Duration.ofMillis(1500));
    return builder.baseUrl(url).requestFactory(factory).build();
  }
}
