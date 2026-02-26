package com.harness.pipeline.ruleengine.evaluator;

import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.model.ConditionDto;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ConditionEvaluator {

  public boolean evaluate(ConditionDto condition, String actualValue) {
    if (condition == null) {
      return false;
    }
    RuleOperator op = condition.operator();
    String expectedValue = condition.value();

    if (actualValue == null) {
      return false;
    }
    if (expectedValue == null) {
      return false;
    }

    return switch (op) {
      case EQUALS -> equalsIgnoreCase(actualValue, expectedValue);
      case NOT_EQUALS -> !equalsIgnoreCase(actualValue, expectedValue);
      case GREATER_THAN ->
          compareNumericOrLex(actualValue, expectedValue) > 0;
      case GREATER_THAN_OR_EQUAL ->
          compareNumericOrLex(actualValue, expectedValue) >= 0;
      case LESS_THAN ->
          compareNumericOrLex(actualValue, expectedValue) < 0;
      case LESS_THAN_OR_EQUAL ->
          compareNumericOrLex(actualValue, expectedValue) <= 0;
      case CONTAINS ->
          actualValue.toLowerCase(Locale.ROOT).contains(expectedValue.toLowerCase(Locale.ROOT));
      case NOT_CONTAINS ->
          !actualValue.toLowerCase(Locale.ROOT).contains(expectedValue.toLowerCase(Locale.ROOT));
      case STARTS_WITH ->
          actualValue.toLowerCase(Locale.ROOT).startsWith(expectedValue.toLowerCase(Locale.ROOT));
      case ENDS_WITH ->
          actualValue.toLowerCase(Locale.ROOT).endsWith(expectedValue.toLowerCase(Locale.ROOT));
      case REGEX_MATCH -> matchesRegex(actualValue, expectedValue);
    };
  }

  private boolean equalsIgnoreCase(String a, String b) {
    return Objects.equals(
        a == null ? null : a.toLowerCase(Locale.ROOT),
        b == null ? null : b.toLowerCase(Locale.ROOT)
    );
  }

  private int compareNumericOrLex(String actual, String expected) {
    try {
      double a = Double.parseDouble(actual);
      double b = Double.parseDouble(expected);
      return Double.compare(a, b);
    } catch (NumberFormatException ex) {
      return actual.compareTo(expected);
    }
  }

  private boolean matchesRegex(String actual, String pattern) {
    try {
      return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(actual).find();
    } catch (PatternSyntaxException ex) {
      return false;
    }
  }
}

