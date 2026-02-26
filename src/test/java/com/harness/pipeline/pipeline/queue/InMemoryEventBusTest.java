package com.harness.pipeline.pipeline.queue;

import com.harness.pipeline.model.ApiEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventBusTest {

  @Test
  void publishRoutesToBothQueuesAndDropsWhenRealtimeFull() throws Exception {
    InMemoryEventBus bus = new InMemoryEventBus(1, 100);

    ApiEvent event1 = demoEvent("e1");
    ApiEvent event2 = demoEvent("e2");

    boolean firstAccepted = bus.publish(event1);
    boolean secondAccepted = bus.publish(event2);

    assertThat(firstAccepted).isTrue();
    assertThat(secondAccepted).isFalse();

    assertThat(bus.getRealtimeQueueSize()).isEqualTo(1);
    assertThat(bus.getBatchQueueSize()).isEqualTo(2);

    // Realtime queue preserves order
    ApiEvent realtime = bus.takeRealtime();
    assertThat(realtime.eventId()).isEqualTo("e1");

    // Batch queue contains both events
    ApiEvent batch1 = bus.takeBatch();
    ApiEvent batch2 = bus.takeBatch();
    assertThat(batch1.eventId()).isEqualTo("e1");
    assertThat(batch2.eventId()).isEqualTo("e2");
  }

  private ApiEvent demoEvent(String id) {
    return new ApiEvent(
        id,
        "tenant-1",
        Instant.now(),
        Instant.now(),
        null,
        null,
        null
    );
  }
}

