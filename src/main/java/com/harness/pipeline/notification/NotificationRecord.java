package com.harness.pipeline.notification;

import java.time.Instant;
import java.util.Map;

public record NotificationRecord(
    String id,
    Instant timestamp,
    String type,
    String tenantId,
    String ruleName,
    String message,
    Map<String, Object> details
) {}
