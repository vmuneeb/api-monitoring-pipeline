package com.harness.pipeline.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

public record ApiEventRequest(
    @NotNull Instant timestamp,
    @Valid @NotNull HttpRequest request,
    @Valid HttpResponse response,
    @Valid ServiceMetadata metadata
) {

  public record HttpRequest(
      @NotBlank String method,
      @NotBlank String host,
      @NotBlank String path,
      String queryString,
      Map<String, String> headers,
      String body,
      Long sizeBytes
  ) {}

  public record HttpResponse(
      @NotNull Integer statusCode,
      @NotNull Long responseTimeMs,
      Map<String, String> headers,
      String body,
      Long sizeBytes
  ) {}

  public record ServiceMetadata(
      @NotBlank String serviceId,
      String serviceName,
      @NotBlank String environment,
      String region,
      String hostIp,
      String traceId,
      Map<String, String> tags
  ) {}
}


