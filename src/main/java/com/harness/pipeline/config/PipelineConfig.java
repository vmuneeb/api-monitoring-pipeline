package com.harness.pipeline.config;

import com.harness.pipeline.pipeline.queue.EventBus;
import com.harness.pipeline.pipeline.queue.InMemoryEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineConfig {

  @Bean
  public EventBus eventBus() {
    // Simple fixed capacities; could be externalized via properties in V2.
    int realtimeCapacity = 10_000;
    int batchCapacity = 50_000;
    return new InMemoryEventBus(realtimeCapacity, batchCapacity);
  }
}

