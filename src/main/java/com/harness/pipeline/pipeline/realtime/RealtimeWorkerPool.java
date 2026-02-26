package com.harness.pipeline.pipeline.realtime;

import com.harness.pipeline.notification.NotificationService;
import com.harness.pipeline.pipeline.queue.EventBus;
import com.harness.pipeline.service.RuleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RealtimeWorkerPool {

  private static final Logger log = LoggerFactory.getLogger(RealtimeWorkerPool.class);

  private final EventBus eventBus;
  private final RuleService ruleService;
  private final NotificationService notificationService;

  private final List<RealtimeWorker> workers = new ArrayList<>();
  private final List<Thread> threads = new ArrayList<>();

  public RealtimeWorkerPool(EventBus eventBus,
                            RuleService ruleService,
                            NotificationService notificationService) {
    this.eventBus = eventBus;
    this.ruleService = ruleService;
    this.notificationService = notificationService;
  }

  @PostConstruct
  public void start() {
    int workerCount = 2; // simple fixed number, could come from config
    for (int i = 0; i < workerCount; i++) {
      RealtimeWorker worker = new RealtimeWorker(eventBus, ruleService, notificationService);
      Thread thread = new Thread(worker, "realtime-worker-" + i);
      thread.setDaemon(true);
      workers.add(worker);
      threads.add(thread);
      thread.start();
    }
    log.info("Started {} realtime worker threads", workerCount);
  }

  @PreDestroy
  public void stop() {
    workers.forEach(RealtimeWorker::shutdown);
    threads.forEach(Thread::interrupt);
  }
}

