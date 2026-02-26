package com.harness.pipeline.controller;

import com.harness.pipeline.model.ApiEventRequest;
import com.harness.pipeline.service.EventIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
public class EventIngestionController {

  private static final String TENANT_HEADER = "X-Tenant-Id";

  private final EventIngestionService ingestionService;

  public EventIngestionController(EventIngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  @PostMapping("/events")
  public ResponseEntity<EventResponse> ingestSingle(
      @RequestHeader(TENANT_HEADER) @NotBlank String tenantId,
      @Valid @RequestBody ApiEventRequest request
  ) {
    String eventId = ingestionService.ingestEvent(tenantId, request);
    return ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .body(new EventResponse(eventId));
  }

  public record EventResponse(String eventId) {}
}


