package com.harness.pipeline.service;

import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.ApiEventRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

  public String ingestEvent(String tenantId, ApiEventRequest request) {
    ApiEvent event = toDomainEvent(tenantId, request);
    // In a later commit we will route this event into the realtime and batch pipelines.
    return event.eventId();
  }

  private ApiEvent toDomainEvent(String tenantId, ApiEventRequest request) {
    String eventId = UUID.randomUUID().toString();
    Instant now = Instant.now();

    ApiEvent.HttpRequest httpRequest = null;
    if (request.request() != null) {
      httpRequest = new ApiEvent.HttpRequest(
          request.request().method(),
          request.request().host(),
          request.request().path(),
          request.request().queryString(),
          request.request().headers(),
          request.request().body(),
          request.request().sizeBytes()
      );
    }

    ApiEvent.HttpResponse httpResponse = null;
    if (request.response() != null) {
      Integer statusCode = request.response().statusCode();
      String statusClass = null;
      if (statusCode != null) {
        int hundred = statusCode / 100;
        statusClass = hundred + "xx";
      }
      httpResponse = new ApiEvent.HttpResponse(
          statusCode,
          statusClass,
          request.response().responseTimeMs(),
          request.response().headers(),
          request.response().body(),
          request.response().sizeBytes()
      );
    }

    ApiEvent.ServiceMetadata metadata = null;
    if (request.metadata() != null) {
      metadata = new ApiEvent.ServiceMetadata(
          request.metadata().serviceId(),
          request.metadata().serviceName(),
          request.metadata().environment(),
          request.metadata().region(),
          request.metadata().hostIp(),
          request.metadata().traceId(),
          request.metadata().tags()
      );
    }

    return new ApiEvent(
        eventId,
        tenantId,
        request.timestamp(),
        now,
        httpRequest,
        httpResponse,
        metadata
    );
  }
}


