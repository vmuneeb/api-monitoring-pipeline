package com.harness.pipeline.model;

import com.harness.pipeline.enums.ConditionGroupOperator;
import java.util.List;

public record ConditionGroupDto(
    ConditionGroupOperator operator,
    List<ConditionDto> conditions
) {}

