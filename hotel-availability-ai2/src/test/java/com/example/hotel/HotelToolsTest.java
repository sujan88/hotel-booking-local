package com.example.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotel.dto.*;
import com.example.hotel.service.AvailabilityService;
import com.example.hotel.tool.HotelTools;
import org.junit.jupiter.api.Test;

class HotelToolsTest {
  private final AvailabilityService service = mock(AvailabilityService.class);
  private final HotelTools tools = new HotelTools(service);

  @Test
  void invalidDatesNeverCallService() {
    assertThat(tools.searchAvailability("h1", "bad", "2027-01-02", 2).errorCode())
        .isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(service);
  }

  @Test
  void reversedDatesNeverCallService() {
    assertThat(tools.searchAvailability("h1", "2027-01-03", "2027-01-02", 2).retryable()).isFalse();
    verifyNoInteractions(service);
  }

  @Test
  void returnsSynchronousStructuredResponse() {
    var response = AvailabilityResponse.success(java.util.List.of());
    when(service.search(any())).thenReturn(response);
    assertThat(tools.searchAvailability("h1", "2027-01-01", "2027-01-02", 2)).isSameAs(response);
  }
}
