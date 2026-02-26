package com.harness.pipeline.notification;

import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.RuleDto;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoggingNotificationService implements NotificationService {

  private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

  private final NotificationBuffer buffer;

  public LoggingNotificationService(NotificationBuffer buffer) {
    this.buffer = buffer;
  }

  @Override
  public void notify(ApiEvent event, RuleDto rule) {
    String path = event.request() != null ? event.request().path() : null;
    Integer statusCode = event.response() != null ? event.response().statusCode() : null;
    String env = event.metadata() != null ? event.metadata().environment() : null;

    log.warn(
        "NOTIFICATION FIRED: tenantId={}, ruleName={}, ruleType={}, statusCode={}, path={}, env={}",
        event.tenantId(),
        rule.name(),
        rule.type(),
        statusCode,
        path,
        env
    );

    buffer.push(new NotificationRecord(
        UUID.randomUUID().toString(),
        Instant.now(),
        "REALTIME",
        event.tenantId(),
        rule.name(),
        "Rule triggered: " + rule.name(),
        Map.of(
            "statusCode", statusCode != null ? statusCode : "N/A",
            "path", path != null ? path : "N/A",
            "environment", env != null ? env : "N/A",
            "eventId", event.eventId() != null ? event.eventId() : "N/A"
        )
    ));
  }

  @Override
  public void notifyBatchThresholdBreached(RuleDto rule, long count) {
    log.warn(
        "BATCH THRESHOLD BREACHED: tenantId={}, ruleName={}, threshold={}, actualCount={}, windowMinutes={}",
        rule.tenantId(),
        rule.name(),
        rule.countThreshold(),
        count,
        rule.windowMinutes()
    );

    buffer.push(new NotificationRecord(
        UUID.randomUUID().toString(),
        Instant.now(),
        "BATCH",
        rule.tenantId(),
        rule.name(),
        "Threshold breached: " + count + " >= " + rule.countThreshold(),
        Map.of(
            "actualCount", count,
            "threshold", rule.countThreshold() != null ? rule.countThreshold() : 0,
            "windowMinutes", rule.windowMinutes() != null ? rule.windowMinutes() : 0
        )
    ));
  }
}
