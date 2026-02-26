package com.harness.pipeline.ruleengine.evaluator;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.RuleDto;
import java.util.ArrayList;
import java.util.List;

public class RealtimeRuleEvaluator {

  private final EventFieldExtractor fieldExtractor;
  private final ConditionEvaluator conditionEvaluator;

  public RealtimeRuleEvaluator(EventFieldExtractor fieldExtractor,
                               ConditionEvaluator conditionEvaluator) {
    this.fieldExtractor = fieldExtractor;
    this.conditionEvaluator = conditionEvaluator;
  }

  public List<RuleDto> evaluate(ApiEvent event, List<RuleDto> rules) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    List<RuleDto> fired = new ArrayList<>();
    for (RuleDto rule : rules) {
      if (rule == null
          || rule.type() != RuleType.REALTIME
          || !rule.enabled()) {
        continue;
      }
      boolean result = evaluateRule(event, rule);
      if (result) {
        fired.add(rule);
      }
    }
    return fired;
  }

  private boolean evaluateRule(ApiEvent event, RuleDto rule) {
    List<ConditionGroupDto> groups = rule.conditionGroups();
    if (groups == null || groups.isEmpty()) {
      return false;
    }
    List<Boolean> groupResults = new ArrayList<>(groups.size());
    for (ConditionGroupDto group : groups) {
      groupResults.add(evaluateGroup(event, group));
    }
    return applyOperator(rule.groupOperator(), groupResults);
  }

  private boolean evaluateGroup(ApiEvent event, ConditionGroupDto group) {
    List<ConditionDto> conditions = group.conditions();
    if (conditions == null || conditions.isEmpty()) {
      return false;
    }
    List<Boolean> results = new ArrayList<>(conditions.size());
    for (ConditionDto condition : conditions) {
      String actual = fieldExtractor.extract(event, condition);
      boolean matched = conditionEvaluator.evaluate(condition, actual);
      results.add(matched);
    }
    return applyOperator(group.operator(), results);
  }

  public boolean applyOperator(ConditionGroupOperator op, List<Boolean> results) {
    if (results == null || results.isEmpty()) {
      return switch (op) {
        case AND -> true;
        case OR -> false;
      };
    }
    return switch (op) {
      case AND -> results.stream().allMatch(Boolean::booleanValue);
      case OR -> results.stream().anyMatch(Boolean::booleanValue);
    };
  }
}

