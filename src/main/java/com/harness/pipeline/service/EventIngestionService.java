package com.harness.pipeline.service;

import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.ApiEventRequest;
import com.harness.pipeline.pipeline.queue.EventBus;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

  private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

  private final EventBus eventBus;

  public EventIngestionService(EventBus eventBus) {
    this.eventBus = eventBus;
  }

  public String ingestEvent(String tenantId, ApiEventRequest request) {
    ApiEvent event = toDomainEvent(tenantId, request);
    boolean acceptedRealtime = eventBus.publish(event);
    if (!acceptedRealtime) {
      log.warn("Realtime queue full, event routed to batch only. tenantId={}, eventId={}",
          tenantId, event.eventId());
    }
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


