package com.harness.pipeline.model;

import java.time.Instant;
import java.util.Map;

public record ApiEvent(
    String eventId,
    String tenantId,
    Instant timestamp,
    Instant receivedAt,
    HttpRequest request,
    HttpResponse response,
    ServiceMetadata metadata
) {

  public record HttpRequest(
      String method,
      String host,
      String path,
      String queryString,
      Map<String, String> headers,
      String body,
      Long sizeBytes
  ) {}

  public record HttpResponse(
      Integer statusCode,
      String statusClass,
      Long responseTimeMs,
      Map<String, String> headers,
      String body,
      Long sizeBytes
  ) {}

  public record ServiceMetadata(
      String serviceId,
      String serviceName,
      String environment,
      String region,
      String hostIp,
      String traceId,
      Map<String, String> tags
  ) {}
}

