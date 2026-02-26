package com.harness.pipeline.ruleengine;

import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.ruleengine.evaluator.ConditionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

  private ConditionEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new ConditionEvaluator();
  }

  @Test
  void equalsAndNotEqualsAreCaseInsensitive() {
    ConditionDto equals = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CLASS,
        RuleOperator.EQUALS,
        "5XX"
    );
    ConditionDto notEquals = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CLASS,
        RuleOperator.NOT_EQUALS,
        "4xx"
    );

    assertThat(evaluator.evaluate(equals, "5xx")).isTrue();
    assertThat(evaluator.evaluate(equals, "5Xx")).isTrue();
    assertThat(evaluator.evaluate(notEquals, "5xx")).isTrue();
    assertThat(evaluator.evaluate(notEquals, "4xx")).isFalse();
  }

  @Test
  void numericComparisonsUseDoubleAndFallbackToLexicographic() {
    ConditionDto gt = new ConditionDto(
        RuleConditionField.RESPONSE_TIME_MS,
        RuleOperator.GREATER_THAN,
        "100"
    );
    ConditionDto gte = new ConditionDto(
        RuleConditionField.RESPONSE_TIME_MS,
        RuleOperator.GREATER_THAN_OR_EQUAL,
        "100"
    );

    assertThat(evaluator.evaluate(gt, "150")).isTrue();
    assertThat(evaluator.evaluate(gt, "50")).isFalse();
    assertThat(evaluator.evaluate(gte, "100")).isTrue();

    ConditionDto gtLex = new ConditionDto(
        RuleConditionField.RESPONSE_TIME_MS,
        RuleOperator.GREATER_THAN,
        "abc"
    );
    assertThat(evaluator.evaluate(gtLex, "bcd")).isTrue();
    assertThat(evaluator.evaluate(gtLex, "aaa")).isFalse();
  }

  @Test
  void containsStartsEndsWithAreCaseInsensitive() {
    ConditionDto contains = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.CONTAINS,
        "/PAY"
    );
    ConditionDto notContains = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.NOT_CONTAINS,
        "/admin"
    );
    ConditionDto startsWith = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.STARTS_WITH,
        "/API"
    );
    ConditionDto endsWith = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.ENDS_WITH,
        "MENTS"
    );

    String actual = "/api/payments";

    assertThat(evaluator.evaluate(contains, actual)).isTrue();
    assertThat(evaluator.evaluate(notContains, actual)).isTrue();
    assertThat(evaluator.evaluate(startsWith, actual)).isTrue();
    assertThat(evaluator.evaluate(endsWith, actual)).isTrue();
  }

  @Test
  void regexMatchHandlesInvalidPatternsSafely() {
    ConditionDto valid = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.REGEX_MATCH,
        ".*payments.*"
    );
    ConditionDto invalid = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.REGEX_MATCH,
        "*["
    );

    assertThat(evaluator.evaluate(valid, "/api/payments")).isTrue();
    assertThat(evaluator.evaluate(valid, "/api/users")).isFalse();
    assertThat(evaluator.evaluate(invalid, "/api/payments")).isFalse();
  }

  @Test
  void nullActualOrExpectedAlwaysFalse() {
    ConditionDto equals = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.EQUALS,
        "/api"
    );
    assertThat(evaluator.evaluate(equals, null)).isFalse();

    ConditionDto noExpected = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.EQUALS,
        null
    );
    assertThat(evaluator.evaluate(noExpected, "/api")).isFalse();
  }
}

