package com.harness.pipeline.pipeline.batch;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.RuleDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Translates a BATCH rule's conditions into a DuckDB SQL query of the form:
 *   SELECT COUNT(*) FROM read_json_auto('{glob}') WHERE {conditions} AND received_at >= {windowStart}
 */
@Component
public class BatchRuleQueryBuilder {

  private static final Map<RuleConditionField, String> FIELD_TO_COLUMN = Map.ofEntries(
      Map.entry(RuleConditionField.REQUEST_METHOD, "http_method"),
      Map.entry(RuleConditionField.REQUEST_HOST, "request_host"),
      Map.entry(RuleConditionField.REQUEST_PATH, "request_path"),
      Map.entry(RuleConditionField.REQUEST_QUERY_STRING, "query_string"),
      Map.entry(RuleConditionField.RESPONSE_STATUS_CODE, "status_code"),
      Map.entry(RuleConditionField.RESPONSE_STATUS_CLASS, "status_class"),
      Map.entry(RuleConditionField.RESPONSE_TIME_MS, "response_time_ms"),
      Map.entry(RuleConditionField.METADATA_ENVIRONMENT, "environment"),
      Map.entry(RuleConditionField.METADATA_REGION, "region")
  );

  private static final Map<RuleOperator, String> NUMERIC_OPERATORS = Map.of(
      RuleOperator.EQUALS, "=",
      RuleOperator.NOT_EQUALS, "!=",
      RuleOperator.GREATER_THAN, ">",
      RuleOperator.GREATER_THAN_OR_EQUAL, ">=",
      RuleOperator.LESS_THAN, "<",
      RuleOperator.LESS_THAN_OR_EQUAL, "<="
  );

  /**
   * Build a complete DuckDB query for the given rule and file glob.
   * Returns null if the rule has no translatable conditions.
   */
  public String buildCountQuery(RuleDto rule, String fileGlob, Instant windowStart) {
    String whereClause = buildWhereClause(rule);

    StringBuilder sb = new StringBuilder();
    sb.append("SELECT COUNT(*) FROM read_json_auto(");
    String[] paths = fileGlob.split(",");
    if (paths.length == 1) {
      sb.append("'").append(paths[0]).append("'");
    } else {
      sb.append("[");
      for (int i = 0; i < paths.length; i++) {
        if (i > 0) sb.append(", ");
        sb.append("'").append(paths[i]).append("'");
      }
      sb.append("]");
    }
    sb.append(")");
    sb.append(" WHERE received_at >= ").append(windowStart.toEpochMilli());

    if (whereClause != null && !whereClause.isBlank()) {
      sb.append(" AND (").append(whereClause).append(")");
    }

    return sb.toString();
  }

  /**
   * Build just the WHERE fragment from the rule's condition groups.
   * Visible for testing.
   */
  public String buildWhereClause(RuleDto rule) {
    List<ConditionGroupDto> groups = rule.conditionGroups();
    if (groups == null || groups.isEmpty()) {
      return null;
    }

    ConditionGroupOperator topOp = rule.groupOperator() != null
        ? rule.groupOperator()
        : ConditionGroupOperator.AND;
    String topJoiner = topOp == ConditionGroupOperator.OR ? " OR " : " AND ";

    StringJoiner groupJoiner = new StringJoiner(topJoiner);
    for (ConditionGroupDto group : groups) {
      String groupSql = buildGroupFragment(group);
      if (groupSql != null) {
        groupJoiner.add("(" + groupSql + ")");
      }
    }

    String result = groupJoiner.toString();
    return result.isEmpty() ? null : result;
  }

  private String buildGroupFragment(ConditionGroupDto group) {
    List<ConditionDto> conditions = group.conditions();
    if (conditions == null || conditions.isEmpty()) {
      return null;
    }

    ConditionGroupOperator op = group.operator() != null
        ? group.operator()
        : ConditionGroupOperator.AND;
    String joiner = op == ConditionGroupOperator.OR ? " OR " : " AND ";

    StringJoiner sj = new StringJoiner(joiner);
    for (ConditionDto cond : conditions) {
      String fragment = buildConditionFragment(cond);
      if (fragment != null) {
        sj.add(fragment);
      }
    }

    String result = sj.toString();
    return result.isEmpty() ? null : result;
  }

  private String buildConditionFragment(ConditionDto cond) {
    String column = FIELD_TO_COLUMN.get(cond.field());
    if (column == null) {
      return null;
    }

    RuleOperator op = cond.operator();
    String value = cond.value();

    if (isNumericColumn(cond.field())) {
      return buildNumericCondition(column, op, value);
    }
    return buildStringCondition(column, op, value);
  }

  private boolean isNumericColumn(RuleConditionField field) {
    return field == RuleConditionField.RESPONSE_STATUS_CODE
        || field == RuleConditionField.RESPONSE_TIME_MS;
  }

  private String buildNumericCondition(String column, RuleOperator op, String value) {
    String sqlOp = NUMERIC_OPERATORS.get(op);
    if (sqlOp != null) {
      return column + " " + sqlOp + " " + value;
    }
    return null;
  }

  private String buildStringCondition(String column, RuleOperator op, String value) {
    String escaped = escapeSql(value);
    return switch (op) {
      case EQUALS -> column + " = '" + escaped + "'";
      case NOT_EQUALS -> column + " != '" + escaped + "'";
      case CONTAINS -> column + " LIKE '%" + escaped + "%'";
      case NOT_CONTAINS -> column + " NOT LIKE '%" + escaped + "%'";
      case STARTS_WITH -> column + " LIKE '" + escaped + "%'";
      case ENDS_WITH -> column + " LIKE '%" + escaped + "'";
      case REGEX_MATCH -> "regexp_matches(" + column + ", '" + escaped + "')";
      default -> null;
    };
  }

  private String escapeSql(String value) {
    if (value == null) return "";
    return value.replace("'", "''");
  }
}
