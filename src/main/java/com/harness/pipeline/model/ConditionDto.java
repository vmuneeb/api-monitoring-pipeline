package com.harness.pipeline.model;

import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;

public record ConditionDto(
    RuleConditionField field,
    RuleOperator operator,
    String value
) {}

