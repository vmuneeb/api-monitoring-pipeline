package com.harness.pipeline.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.pipeline.model.ApiEventRequest;
import com.harness.pipeline.service.EventIngestionService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventIngestionController.class)
class EventIngestionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private EventIngestionService ingestionService;

  @Test
  void ingestSingleReturns202AndEventId() throws Exception {
    given(ingestionService.ingestEvent(eq("tenant-1"), any(ApiEventRequest.class)))
        .willReturn("event-123");

    ApiEventRequest request = new ApiEventRequest(
        Instant.parse("2026-02-26T12:00:00Z"),
        new ApiEventRequest.HttpRequest(
            "POST",
            "api.example.com",
            "/api/payments",
            "page=1",
            Map.of("Content-Type", "application/json"),
            "{\"amount\":100}",
            20L
        ),
        new ApiEventRequest.HttpResponse(
            500,
            123L,
            Map.of(),
            "{\"error\":\"internal\"}",
            10L
        ),
        new ApiEventRequest.ServiceMetadata(
            "svc-1",
            "payment-svc",
            "prod",
            "us-east-1",
            "127.0.0.1",
            "trace-1",
            Map.of("team", "payments")
        )
    );

    mockMvc.perform(
            post("/api/v1/events")
                .header("X-Tenant-Id", "tenant-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.eventId").value("event-123"));
  }

  @Test
  void missingTenantHeaderReturns400() throws Exception {
    ApiEventRequest request = new ApiEventRequest(
        Instant.parse("2026-02-26T12:00:00Z"),
        new ApiEventRequest.HttpRequest(
            "GET",
            "api.example.com",
            "/api/users",
            null,
            Map.of(),
            null,
            null
        ),
        new ApiEventRequest.HttpResponse(
            200,
            50L,
            Map.of(),
            null,
            null
        ),
        new ApiEventRequest.ServiceMetadata(
            "svc-1",
            "user-svc",
            "prod",
            null,
            null,
            null,
            Map.of()
        )
    );

    mockMvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidBodyReturns400() throws Exception {
    // Missing required fields like timestamp and request.method
    String payload = """
        {
          "request": {
            "host": "api.example.com",
            "path": "/api/users"
          }
        }
        """;

    mockMvc.perform(
            post("/api/v1/events")
                .header("X-Tenant-Id", "tenant-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
        .andExpect(status().isBadRequest());
  }
}

