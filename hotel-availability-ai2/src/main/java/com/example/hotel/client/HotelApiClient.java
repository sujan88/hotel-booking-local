package com.example.hotel.client;

import com.example.hotel.dto.*;
import com.example.hotel.exception.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class HotelApiClient {
  private final RestClient client;

  public HotelApiClient(RestClient hotelRestClient) {
    this.client = hotelRestClient;
  }

  public AvailabilityResponse search(AvailabilityRequest request) {
    try {
      var rooms =
          client
              .get()
              .uri(
                  b ->
                      b.path("/hotels/{id}/availability")
                          .queryParam("checkIn", request.checkIn())
                          .queryParam("checkOut", request.checkOut())
                          .queryParam("guests", request.guests())
                          .build(request.hotelId()))
              .retrieve()
              .body(Room[].class);
      if (rooms == null) throw new AvailabilityUpstreamException("Empty upstream response");
      return AvailabilityResponse.success(java.util.List.of(rooms));
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 429)
        throw new AvailabilityTransientException("Upstream rate limited", e);
      if (e.getStatusCode().value() == 400
          || e.getStatusCode().value() == 404
          || e.getStatusCode().value() == 422)
        throw new AvailabilityBadRequestException("Hotel search rejected", e);
      throw new AvailabilityUpstreamException("Upstream client error", e);
    } catch (HttpServerErrorException | ResourceAccessException e) {
      throw new AvailabilityTransientException("Upstream unavailable", e);
    } catch (RestClientException e) {
      throw new AvailabilityUpstreamException("Invalid upstream response", e);
    }
  }

  public Reservation createReservation(ReservationRequest request, String idempotencyKey) {
    try {
      var result =
          client
              .post()
              .uri("/reservations")
              .header("Idempotency-Key", idempotencyKey)
              .body(request)
              .retrieve()
              .body(Reservation.class);
      if (result == null
          || result.reservationId() == null
          || result.reservationId().isBlank()
          || !java.util.Set.of("CONFIRMED", "PENDING_PAYMENT", "PAYMENT_UNKNOWN", "CANCELLED")
              .contains(result.status() == null ? "" : result.status()))
        throw new ReservationUpstreamException("Unconfirmed reservation response");
      return result;
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 429)
        throw new ReservationTransientException("Hotel write rate limited", e);
      if (e.getStatusCode().value() == 402)
        throw new ReservationRejectedException("PAYMENT_REQUIRED");
      if (e.getStatusCode().value() == 409) {
        String body = e.getResponseBodyAsString();
        throw new ReservationRejectedException(
            body.contains("INVENTORY_UNAVAILABLE")
                ? "INVENTORY_UNAVAILABLE"
                : "IDEMPOTENCY_CONFLICT");
      }
      if (e.getStatusCode().value() == 400
          || e.getStatusCode().value() == 404
          || e.getStatusCode().value() == 422)
        throw new ReservationRejectedException("INVALID_REQUEST");
      throw new ReservationRejectedException("UPSTREAM_REJECTED");
    } catch (HttpServerErrorException | ResourceAccessException e) {
      throw new ReservationTransientException("Hotel write response unavailable", e);
    } catch (RestClientException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause instanceof java.net.SocketTimeoutException
            || cause instanceof java.net.SocketException
            || cause instanceof java.net.http.HttpTimeoutException
            || cause instanceof java.io.EOFException)
          throw new ReservationTransientException("Hotel write response interrupted", e);
      }
      throw new ReservationUpstreamException("Hotel write response could not be parsed", e);
    }
  }

  public Reservation getReservationStatus(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
      throw new ReservationRejectedException("INVALID_REQUEST");
    try {
      var result = client.get().uri("/reservations/{id}", id).retrieve().body(Reservation.class);
      if (result == null
          || !id.equals(result.reservationId())
          || !java.util.Set.of("CONFIRMED", "PENDING_PAYMENT", "PAYMENT_UNKNOWN", "CANCELLED")
              .contains(result.status() == null ? "" : result.status()))
        throw new ReservationUpstreamException("Invalid status response");
      return result;
    } catch (HttpClientErrorException.NotFound e) {
      throw new ReservationRejectedException("RESERVATION_NOT_FOUND");
    } catch (HttpClientErrorException e) {
      throw new ReservationRejectedException("UPSTREAM_REJECTED");
    } catch (RestClientException e) {
      throw new ReservationTransientException("Status unavailable", e);
    }
  }
}
