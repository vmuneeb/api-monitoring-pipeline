package com.harness.pipeline.notification;

import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.RuleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoggingNotificationService implements NotificationService {

  private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

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
  }
}

