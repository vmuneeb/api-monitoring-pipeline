package com.harness.pipeline.pipeline.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.NotificationConfigDto;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.notification.NotificationService;
import com.harness.pipeline.service.RuleService;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class BatchRuleEvaluatorTest {

  @TempDir
  Path tempDir;

  private RuleService ruleService;
  private NotificationService notificationService;
  private BatchRuleEvaluator evaluator;

  @BeforeEach
  void setUp() {
    ruleService = Mockito.mock(RuleService.class);
    notificationService = Mockito.mock(NotificationService.class);
    BatchRuleQueryBuilder queryBuilder = new BatchRuleQueryBuilder();

    evaluator = new BatchRuleEvaluator(
        ruleService, queryBuilder, notificationService, tempDir.toString());
  }

  @Test
  void thresholdBreached_firesNotification() throws Exception {
    String tenantId = "tenant-abc";
    writeEvents(tenantId, List.of(
        eventJson("POST", "/api/orders", 500, "5xx"),
        eventJson("GET", "/api/health", 500, "5xx"),
        eventJson("POST", "/api/users", 502, "5xx")
    ));

    RuleDto rule = batchRule(tenantId, "High 5xx", 60, 2L,
        new ConditionDto(RuleConditionField.RESPONSE_STATUS_CODE, RuleOperator.GREATER_THAN_OR_EQUAL, "500"));

    given(ruleService.listAllEnabledByType(RuleType.BATCH)).willReturn(List.of(rule));

    evaluator.evaluateAllBatchRules();

    verify(notificationService).notifyBatchThresholdBreached(eq(rule), eq(3L));
  }

  @Test
  void thresholdNotBreached_noNotification() throws Exception {
    String tenantId = "tenant-abc";
    writeEvents(tenantId, List.of(
        eventJson("GET", "/api/health", 200, "2xx")
    ));

    RuleDto rule = batchRule(tenantId, "High 5xx", 60, 10L,
        new ConditionDto(RuleConditionField.RESPONSE_STATUS_CODE, RuleOperator.GREATER_THAN_OR_EQUAL, "500"));

    given(ruleService.listAllEnabledByType(RuleType.BATCH)).willReturn(List.of(rule));

    evaluator.evaluateAllBatchRules();

    verify(notificationService, never()).notifyBatchThresholdBreached(any(), Mockito.anyLong());
  }

  @Test
  void noDataForTenant_noNotification() {
    RuleDto rule = batchRule("no-data-tenant", "Missing data", 60, 1L,
        new ConditionDto(RuleConditionField.RESPONSE_STATUS_CODE, RuleOperator.GREATER_THAN_OR_EQUAL, "500"));

    given(ruleService.listAllEnabledByType(RuleType.BATCH)).willReturn(List.of(rule));

    evaluator.evaluateAllBatchRules();

    verify(notificationService, never()).notifyBatchThresholdBreached(any(), Mockito.anyLong());
  }

  private void writeEvents(String tenantId, List<String> jsonLines) throws Exception {
    ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
    Path partitionDir = tempDir.resolve(String.format(
        "tenant_id=%s/year=%04d/month=%02d/day=%02d",
        tenantId, now.getYear(), now.getMonthValue(), now.getDayOfMonth()));
    Files.createDirectories(partitionDir);

    Path file = partitionDir.resolve("test-events.jsonl");
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (String line : jsonLines) {
        writer.write(line);
        writer.newLine();
      }
    }
  }

  private String eventJson(String method, String path, int statusCode, String statusClass) {
    long now = Instant.now().toEpochMilli();
    return String.format(
        "{\"event_id\":\"%s\",\"tenant_id\":\"tenant-abc\",\"timestamp\":%d,\"received_at\":%d," +
        "\"http_method\":\"%s\",\"request_host\":\"api.example.com\",\"request_path\":\"%s\"," +
        "\"query_string\":null,\"status_code\":%d,\"status_class\":\"%s\"," +
        "\"response_time_ms\":120,\"environment\":\"prod\",\"region\":\"us-east-1\"," +
        "\"service_id\":\"svc-1\",\"trace_id\":\"trace-1\"}",
        UUID.randomUUID(), now, now, method, path, statusCode, statusClass);
  }

  private RuleDto batchRule(String tenantId, String name, int windowMinutes, long threshold,
                            ConditionDto... conditions) {
    ConditionGroupDto group = new ConditionGroupDto(
        ConditionGroupOperator.AND, List.of(conditions));
    return new RuleDto(
        UUID.randomUUID(),
        tenantId,
        name,
        RuleType.BATCH,
        true,
        ConditionGroupOperator.AND,
        List.of(group),
        new NotificationConfigDto("LOG", "console"),
        windowMinutes,
        threshold,
        Instant.now(),
        Instant.now()
    );
  }
}
