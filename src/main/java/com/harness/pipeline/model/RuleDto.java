package com.harness.pipeline.model;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuleDto(
    UUID id,
    String tenantId,
    String name,
    RuleType type,
    boolean enabled,
    ConditionGroupOperator groupOperator,
    List<ConditionGroupDto> conditionGroups,
    NotificationConfigDto notificationConfig,
    Instant createdAt,
    Instant updatedAt
) {}

