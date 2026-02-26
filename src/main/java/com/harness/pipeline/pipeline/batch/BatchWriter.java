package com.harness.pipeline.pipeline.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.pipeline.queue.EventBus;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BatchWriter {

  private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);

  private final EventBus eventBus;
  private final ObjectMapper objectMapper;
  private final String basePath;

  public BatchWriter(EventBus eventBus,
                     ObjectMapper objectMapper,
                     @Value("${pipeline.batch.base-path:/tmp/api-event-pipeline/batch-events}") String basePath) {
    this.eventBus = eventBus;
    this.objectMapper = objectMapper;
    this.basePath = basePath;
  }

  /**
   * Drain all currently available events from the batch queue and write them to Parquet
   * files partitioned by tenant and event date.
   */
  public void flushAllFromQueue() {
    List<ApiEvent> drained = new ArrayList<>();
    // Block for at least one event if any exist; return quickly if queue is empty.
    int initialSize = eventBus.getBatchQueueSize();
    if (initialSize == 0) {
      return;
    }
    try {
      drained.add(eventBus.takeBatch());
      int remaining = eventBus.getBatchQueueSize();
      for (int i = 0; i < remaining; i++) {
        drained.add(eventBus.takeBatch());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while draining batch queue", e);
    }

    if (drained.isEmpty()) {
      return;
    }

    Map<String, List<ApiEvent>> byPartition = groupByTenantAndDate(drained);
    int fileCount = 0;
    for (Map.Entry<String, List<ApiEvent>> entry : byPartition.entrySet()) {
      String partitionPath = entry.getKey();
      List<ApiEvent> events = entry.getValue();
      try {
        writePartition(partitionPath, events);
        fileCount++;
      } catch (IOException e) {
        log.error("Failed to write Parquet file for partition {}", partitionPath, e);
      }
    }

    log.info("BatchWriter flushed {} events into {} Parquet file(s)", drained.size(), fileCount);
  }

  private Map<String, List<ApiEvent>> groupByTenantAndDate(List<ApiEvent> events) {
    Map<String, List<ApiEvent>> byPartition = new HashMap<>();
    for (ApiEvent event : events) {
      Instant ts = event.timestamp() != null ? event.timestamp() : event.receivedAt();
      ZonedDateTime zdt = ts.atZone(ZoneOffset.UTC);
      String partition = String.format(
          "%s/tenant_id=%s/year=%04d/month=%02d/day=%02d",
          basePath,
          event.tenantId(),
          zdt.getYear(),
          zdt.getMonthValue(),
          zdt.getDayOfMonth()
      );
      byPartition.computeIfAbsent(partition, k -> new ArrayList<>()).add(event);
    }
    return byPartition;
  }

  private void writePartition(String partitionDir, List<ApiEvent> events) throws IOException {
    Files.createDirectories(Path.of(partitionDir));
    String fileName = "events-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".jsonl";
    Path outputPath = Path.of(partitionDir, fileName);

    try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
      for (ApiEvent event : events) {
        writer.write(objectMapper.writeValueAsString(toRecord(event)));
        writer.newLine();
      }
    }
  }

  private Map<String, Object> toRecord(ApiEvent event) {
    Map<String, Object> record = new HashMap<>();
    record.put("event_id", event.eventId());
    record.put("tenant_id", event.tenantId());
    record.put("timestamp", event.timestamp() != null ? event.timestamp().toEpochMilli() : null);
    record.put("received_at", event.receivedAt() != null ? event.receivedAt().toEpochMilli() : null);

    ApiEvent.HttpRequest req = event.request();
    record.put("http_method", req != null ? req.method() : null);
    record.put("request_host", req != null ? req.host() : null);
    record.put("request_path", req != null ? req.path() : null);
    record.put("query_string", req != null ? req.queryString() : null);

    ApiEvent.HttpResponse res = event.response();
    record.put("status_code", res != null ? res.statusCode() : null);
    record.put("status_class", res != null ? res.statusClass() : null);
    record.put("response_time_ms", res != null ? res.responseTimeMs() : null);

    ApiEvent.ServiceMetadata md = event.metadata();
    record.put("environment", md != null ? md.environment() : null);
    record.put("region", md != null ? md.region() : null);
    record.put("service_id", md != null ? md.serviceId() : null);
    record.put("trace_id", md != null ? md.traceId() : null);

    return record;
  }
}

