package com.harness.pipeline.notification;

import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.RuleDto;

public interface NotificationService {

  void notify(ApiEvent event, RuleDto rule);
}

