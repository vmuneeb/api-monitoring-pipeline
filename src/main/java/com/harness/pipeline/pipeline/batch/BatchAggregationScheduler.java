package com.harness.pipeline.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BatchAggregationScheduler {

  private static final Logger log = LoggerFactory.getLogger(BatchAggregationScheduler.class);

  private final BatchRuleEvaluator batchRuleEvaluator;

  public BatchAggregationScheduler(BatchRuleEvaluator batchRuleEvaluator) {
    this.batchRuleEvaluator = batchRuleEvaluator;
  }

  @Scheduled(fixedDelayString = "${pipeline.batch.aggregation-interval-ms:300000}")
  public void runBatchAggregation() {
    log.info("Running scheduled batch rule aggregation");
    try {
      batchRuleEvaluator.evaluateAllBatchRules();
    } catch (Exception e) {
      log.error("Batch aggregation failed", e);
    }
  }
}
