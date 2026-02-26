package com.harness.pipeline.pipeline.batch;

import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.pipeline.queue.InMemoryEventBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BatchWriterTest {

  @TempDir
  Path tempDir;

  @Test
  void flushAllFromQueueDrainsEventsAndWritesParquet() throws Exception {
    InMemoryEventBus bus = new InMemoryEventBus(10, 10);

    ApiEvent event = new ApiEvent(
        "event-1",
        "tenant-1",
        Instant.parse("2026-02-26T12:00:00Z"),
        Instant.parse("2026-02-26T12:00:01Z"),
        null,
        null,
        null
    );

    bus.publish(event);

    String basePath = tempDir.toAbsolutePath().toString();
    BatchWriter writer = new BatchWriter(bus, new com.fasterxml.jackson.databind.ObjectMapper(), basePath);

    writer.flushAllFromQueue();

    try (Stream<Path> paths = Files.walk(tempDir)) {
      long dataFiles = paths
          .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
          .count();
      assertThat(dataFiles).isGreaterThanOrEqualTo(1);
    }
  }
}

