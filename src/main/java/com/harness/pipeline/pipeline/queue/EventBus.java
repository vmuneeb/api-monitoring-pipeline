package com.harness.pipeline.pipeline.queue;

import com.harness.pipeline.model.ApiEvent;

public interface EventBus {

  /**
   * Publish an event to both realtime and batch queues.
   *
   * @return true if the event was accepted into the realtime queue,
   *         false if the realtime queue was full and the event was only
   *         sent to the batch queue.
   */
  boolean publish(ApiEvent event);

  ApiEvent takeRealtime() throws InterruptedException;

  ApiEvent takeBatch() throws InterruptedException;

  int getRealtimeQueueSize();

  int getBatchQueueSize();
}

