package com.harness.pipeline.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.NotificationConfigDto;
import com.harness.pipeline.model.RuleDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BatchRuleQueryBuilderTest {

  private final BatchRuleQueryBuilder builder = new BatchRuleQueryBuilder();

  @Test
  void buildWhereClause_singleEqualsCondition() {
    RuleDto rule = batchRule(
        ConditionGroupOperator.AND,
        List.of(group(ConditionGroupOperator.AND,
            List.of(new ConditionDto(RuleConditionField.RESPONSE_STATUS_CLASS, RuleOperator.EQUALS, "5xx"))))
    );

    String where = builder.buildWhereClause(rule);
    assertThat(where).isEqualTo("(status_class = '5xx')");
  }

  @Test
  void buildWhereClause_numericGreaterThan() {
    RuleDto rule = batchRule(
        ConditionGroupOperator.AND,
        List.of(group(ConditionGroupOperator.AND,
            List.of(new ConditionDto(RuleConditionField.RESPONSE_STATUS_CODE, RuleOperator.GREATER_THAN_OR_EQUAL, "500"))))
    );

    String where = builder.buildWhereClause(rule);
    assertThat(where).isEqualTo("(status_code >= 500)");
  }

  @Test
  void buildWhereClause_andGroupWithMultipleConditions() {
    RuleDto rule = batchRule(
        ConditionGroupOperator.AND,
        List.of(group(ConditionGroupOperator.AND, List.of(
            new ConditionDto(RuleConditionField.REQUEST_METHOD, RuleOperator.EQUALS, "POST"),
            new ConditionDto(RuleConditionField.RESPONSE_STATUS_CODE, RuleOperator.GREATER_THAN_OR_EQUAL, "500")
        )))
    );

    String where = builder.buildWhereClause(rule);
    assertThat(where).isEqualTo("(http_method = 'POST' AND status_code >= 500)");
  }

  @Test
  void buildWhereClause_orGroupOperator() {
    RuleDto rule = batchRule(
        ConditionGroupOperator.OR,
        List.of(
            group(ConditionGroupOperator.AND,
                List.of(new ConditionDto(RuleConditionField.METADATA_ENVIRONMENT, RuleOperator.EQUALS, "prod"))),
            group(ConditionGroupOperator.AND,
                List.of(new ConditionDto(RuleConditionField.METADATA_REGION, RuleOperator.EQUALS, "us-east-1")))
        )
    );

    String where = builder.buildWhereClause(rule);
    assertThat(where).isEqualTo("(environment = 'prod') OR (region = 'us-east-1')");
  }

  @Test
  void buildWhereClause_containsOperator() {
    RuleDto rule = batchRule(
        ConditionGroupOperator.AND,
        List.of(group(ConditionGroupOperator.AND,
            List.of(new ConditionDto(RuleConditionField.REQUEST_PATH, RuleOperator.CONTAINS, "/api"))))
    );

    String where = builder.buildWhereClause(rule);
    assertThat(where).isEqualTo("(request_path LIKE '%/api%')");
  }

  @Test
  void buildWhereClause_noConditions_returnsNull() {
    RuleDto rule = batchRule(ConditionGroupOperator.AND, List.of());
    String where = builder.buildWhereClause(rule);
    assertThat(where).isNull();
  }

  @Test
  void buildCountQuery_fullQuery() {
    RuleDto rule = batchRule(
        ConditionGroupOperator.AND,
        List.of(group(ConditionGroupOperator.AND,
            List.of(new ConditionDto(RuleConditionField.RESPONSE_STATUS_CODE, RuleOperator.GREATER_THAN_OR_EQUAL, "500"))))
    );

    Instant windowStart = Instant.ofEpochMilli(1700000000000L);
    String sql = builder.buildCountQuery(rule, "/tmp/data/**/*.jsonl", windowStart);

    assertThat(sql).isEqualTo(
        "SELECT COUNT(*) FROM read_json_auto('/tmp/data/**/*.jsonl')" +
        " WHERE received_at >= 1700000000000 AND ((status_code >= 500))");
  }

  @Test
  void buildCountQuery_noConditions_onlyTimeFilter() {
    RuleDto rule = batchRule(ConditionGroupOperator.AND, List.of());
    Instant windowStart = Instant.ofEpochMilli(1700000000000L);
    String sql = builder.buildCountQuery(rule, "/tmp/data/**/*.jsonl", windowStart);

    assertThat(sql).isEqualTo(
        "SELECT COUNT(*) FROM read_json_auto('/tmp/data/**/*.jsonl')" +
        " WHERE received_at >= 1700000000000");
  }

  private ConditionGroupDto group(ConditionGroupOperator op, List<ConditionDto> conditions) {
    return new ConditionGroupDto(op, conditions);
  }

  private RuleDto batchRule(ConditionGroupOperator groupOp, List<ConditionGroupDto> groups) {
    return new RuleDto(
        UUID.randomUUID(),
        "tenant-1",
        "test-batch-rule",
        RuleType.BATCH,
        true,
        groupOp,
        groups,
        new NotificationConfigDto("LOG", "console"),
        60,
        100L,
        Instant.now(),
        Instant.now()
    );
  }
}
