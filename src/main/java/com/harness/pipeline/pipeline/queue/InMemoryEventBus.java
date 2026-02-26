package com.harness.pipeline.pipeline.queue;

import com.harness.pipeline.model.ApiEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InMemoryEventBus implements EventBus {

  private final BlockingQueue<ApiEvent> realtimeQueue;
  private final BlockingQueue<ApiEvent> batchQueue;

  public InMemoryEventBus(
      @Value("${pipeline.queue.realtime-capacity:10000}") int realtimeCapacity,
      @Value("${pipeline.queue.batch-capacity:50000}") int batchCapacity) {
    this.realtimeQueue = new LinkedBlockingQueue<>(realtimeCapacity);
    this.batchQueue = new LinkedBlockingQueue<>(batchCapacity);
  }

  @Override
  public boolean publish(ApiEvent event) {
    boolean acceptedRealtime = realtimeQueue.offer(event);
    try {
      batchQueue.put(event);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while enqueuing event to batch queue", e);
    }
    return acceptedRealtime;
  }

  @Override
  public ApiEvent takeRealtime() throws InterruptedException {
    return realtimeQueue.take();
  }

  @Override
  public ApiEvent takeBatch() throws InterruptedException {
    return batchQueue.take();
  }

  @Override
  public int getRealtimeQueueSize() {
    return realtimeQueue.size();
  }

  @Override
  public int getBatchQueueSize() {
    return batchQueue.size();
  }
}
