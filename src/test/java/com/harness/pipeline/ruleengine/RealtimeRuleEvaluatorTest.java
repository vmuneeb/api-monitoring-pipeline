package com.harness.pipeline.ruleengine;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.NotificationConfigDto;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.ruleengine.evaluator.ConditionEvaluator;
import com.harness.pipeline.ruleengine.evaluator.EventFieldExtractor;
import com.harness.pipeline.ruleengine.evaluator.RealtimeRuleEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeRuleEvaluatorTest {

  private RealtimeRuleEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new RealtimeRuleEvaluator(
        new EventFieldExtractor(),
        new ConditionEvaluator()
    );
  }

  @Test
  void firesSimpleAndRuleWhenAllConditionsMatch() {
    ApiEvent event = demoEvent(500, "/api/payments", "prod");

    ConditionDto status500 = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CODE,
        RuleOperator.GREATER_THAN_OR_EQUAL,
        "500"
    );
    ConditionDto pathPayments = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.STARTS_WITH,
        "/api/payments"
    );

    ConditionGroupDto group = new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(status500, pathPayments)
    );

    RuleDto rule = baseRule(
        "Payment 5xx Alert",
        RuleType.REALTIME,
        true,
        ConditionGroupOperator.AND,
        List.of(group)
    );

    List<RuleDto> fired = evaluator.evaluate(event, List.of(rule));
    assertThat(fired).containsExactly(rule);
  }

  @Test
  void doesNotFireAndRuleWhenAnyConditionFails() {
    ApiEvent event = demoEvent(200, "/api/payments", "prod");

    ConditionDto status500 = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CODE,
        RuleOperator.GREATER_THAN_OR_EQUAL,
        "500"
    );
    ConditionDto pathPayments = new ConditionDto(
        RuleConditionField.REQUEST_PATH,
        RuleOperator.STARTS_WITH,
        "/api/payments"
    );

    ConditionGroupDto group = new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(status500, pathPayments)
    );

    RuleDto rule = baseRule(
        "Payment 5xx Alert",
        RuleType.REALTIME,
        true,
        ConditionGroupOperator.AND,
        List.of(group)
    );

    List<RuleDto> fired = evaluator.evaluate(event, List.of(rule));
    assertThat(fired).isEmpty();
  }

  @Test
  void firesOrRuleWhenAnyGroupMatches() {
    ApiEvent event = demoEvent(500, "/api/orders", "prod");

    ConditionGroupDto groupStatus = new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(new ConditionDto(
            RuleConditionField.RESPONSE_STATUS_CODE,
            RuleOperator.GREATER_THAN_OR_EQUAL,
            "500"
        ))
    );

    ConditionGroupDto groupSlow = new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(
            new ConditionDto(
                RuleConditionField.RESPONSE_TIME_MS,
                RuleOperator.GREATER_THAN,
                "3000"
            ),
            new ConditionDto(
                RuleConditionField.METADATA_ENVIRONMENT,
                RuleOperator.EQUALS,
                "prod"
            )
        )
    );

    RuleDto rule = baseRule(
        "Prod degradation",
        RuleType.REALTIME,
        true,
        ConditionGroupOperator.OR,
        List.of(groupStatus, groupSlow)
    );

    List<RuleDto> fired = evaluator.evaluate(event, List.of(rule));
    assertThat(fired).containsExactly(rule);
  }

  @Test
  void disabledAndBatchRulesAreIgnored() {
    ApiEvent event = demoEvent(500, "/api/payments", "prod");

    RuleDto disabledRealtime = baseRule(
        "Disabled",
        RuleType.REALTIME,
        false,
        ConditionGroupOperator.AND,
        List.of(singleStatus500Group())
    );
    RuleDto batchRule = baseRule(
        "Batch",
        RuleType.BATCH,
        true,
        ConditionGroupOperator.AND,
        List.of(singleStatus500Group())
    );

    List<RuleDto> fired = evaluator.evaluate(event, List.of(disabledRealtime, batchRule));
    assertThat(fired).isEmpty();
  }

  @Test
  void aggregateFieldsInRealtimeExtractorThrow() {
    ApiEvent event = demoEvent(500, "/api/payments", "prod");

    ConditionDto aggregateCondition = new ConditionDto(
        RuleConditionField.AGGREGATE_ERROR_RATE_PERCENT,
        RuleOperator.GREATER_THAN,
        "5.0"
    );
    ConditionGroupDto group = new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(aggregateCondition)
    );
    RuleDto rule = baseRule(
        "Invalid aggregate rule",
        RuleType.REALTIME,
        true,
        ConditionGroupOperator.AND,
        List.of(group)
    );

    assertThatThrownBy(() -> evaluator.evaluate(event, List.of(rule)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Aggregate fields are not supported");
  }

  @Test
  void applyOperatorHandlesEmptyResults() {
    assertThat(
        evaluator.applyOperator(ConditionGroupOperator.AND, List.of())
    ).isTrue();
    assertThat(
        evaluator.applyOperator(ConditionGroupOperator.OR, List.of())
    ).isFalse();
  }

  private ConditionGroupDto singleStatus500Group() {
    ConditionDto status500 = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CODE,
        RuleOperator.GREATER_THAN_OR_EQUAL,
        "500"
    );
    return new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(status500)
    );
  }

  private RuleDto baseRule(
      String name,
      RuleType type,
      boolean enabled,
      ConditionGroupOperator groupOperator,
      List<ConditionGroupDto> groups
  ) {
    return new RuleDto(
        UUID.randomUUID(),
        "tenant-1",
        name,
        type,
        enabled,
        groupOperator,
        groups,
        new NotificationConfigDto("LOG", "console"),
        null,
        null,
        Instant.now(),
        Instant.now()
    );
  }

  private ApiEvent demoEvent(int statusCode, String path, String env) {
    ApiEvent.HttpRequest request = new ApiEvent.HttpRequest(
        "GET",
        "api.example.com",
        path,
        "page=1",
        Map.of("Content-Type", "application/json"),
        "{\"amount\":100}",
        20L
    );
    ApiEvent.HttpResponse response = new ApiEvent.HttpResponse(
        statusCode,
        statusCode >= 500 ? "5xx" : (statusCode >= 400 ? "4xx" : "2xx"),
        4000L,
        Map.of(),
        "{}",
        10L
    );
    ApiEvent.ServiceMetadata metadata = new ApiEvent.ServiceMetadata(
        "svc-1",
        "payment-svc",
        env,
        "us-east-1",
        "127.0.0.1",
        "trace-1",
        Map.of("team", "payments")
    );
    return new ApiEvent(
        UUID.randomUUID().toString(),
        "tenant-1",
        Instant.now(),
        Instant.now(),
        request,
        response,
        metadata
    );
  }
}

