package com.harness.pipeline.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BatchScheduler {

  private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

  private final BatchWriter batchWriter;

  public BatchScheduler(BatchWriter batchWriter) {
    this.batchWriter = batchWriter;
  }

  @Scheduled(fixedDelayString = "${pipeline.batch.flush-interval-ms:600000}")
  public void flushBatchQueue() {
    log.debug("Running scheduled batch flush");
    batchWriter.flushAllFromQueue();
  }
}

