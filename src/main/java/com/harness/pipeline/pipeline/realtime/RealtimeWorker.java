package com.harness.pipeline.pipeline.realtime;

import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.notification.NotificationService;
import com.harness.pipeline.pipeline.queue.EventBus;
import com.harness.pipeline.ruleengine.evaluator.ConditionEvaluator;
import com.harness.pipeline.ruleengine.evaluator.EventFieldExtractor;
import com.harness.pipeline.ruleengine.evaluator.RealtimeRuleEvaluator;
import com.harness.pipeline.service.RuleService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealtimeWorker implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(RealtimeWorker.class);

  private final EventBus eventBus;
  private final RuleService ruleService;
  private final NotificationService notificationService;
  private final RealtimeRuleEvaluator evaluator;

  private volatile boolean running = true;

  public RealtimeWorker(EventBus eventBus,
                        RuleService ruleService,
                        NotificationService notificationService) {
    this.eventBus = eventBus;
    this.ruleService = ruleService;
    this.notificationService = notificationService;
    this.evaluator = new RealtimeRuleEvaluator(
        new EventFieldExtractor(),
        new ConditionEvaluator()
    );
  }

  @Override
  public void run() {
    log.info("RealtimeWorker started");
    while (running && !Thread.currentThread().isInterrupted()) {
      try {
        ApiEvent event = eventBus.takeRealtime();
        List<RuleDto> rules =
            ruleService.listRules(event.tenantId(), RuleType.REALTIME, true);
        List<RuleDto> fired = evaluator.evaluate(event, rules);
        for (RuleDto rule : fired) {
          notificationService.notify(event, rule);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.error("Error in RealtimeWorker loop", e);
      }
    }
    log.info("RealtimeWorker stopped");
  }

  public void shutdown() {
    this.running = false;
  }
}

