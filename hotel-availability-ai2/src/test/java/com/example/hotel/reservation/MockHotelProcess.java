package com.example.hotel.reservation;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Runs the real SQLite mock provider in a separate process, not an in-memory Java fake. */
final class MockHotelProcess implements AutoCloseable {
  final Process process;
  final String baseUrl;

  private MockHotelProcess(Process process, String baseUrl) {
    this.process = process;
    this.baseUrl = baseUrl;
  }

  static MockHotelProcess start() {
    try {
      Path directory = Files.createTempDirectory("hotel-reservation-test-");
      Path log = directory.resolve("server.log");
      var process =
          new ProcessBuilder(
                  "python3",
                  "-u",
                  Path.of("mock/hotel_api.py").toAbsolutePath().toString(),
                  "--port",
                  "0",
                  "--db",
                  directory.resolve("reservations.sqlite3").toString())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline && process.isAlive()) {
        String output = Files.readString(log);
        for (String line : output.split("\\R"))
          if (line.startsWith("Mock hotel API: "))
            return new MockHotelProcess(
                process, line.substring("Mock hotel API: ".length()).strip());
        Thread.sleep(20);
      }
      process.destroyForcibly();
      throw new IllegalStateException("Mock failed to start: " + Files.readString(log));
    } catch (Exception e) {
      throw new IllegalStateException(
          "Python 3 is required for the reservation HTTP integration tests", e);
    }
  }

  @Override
  public void close() {
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }
}
