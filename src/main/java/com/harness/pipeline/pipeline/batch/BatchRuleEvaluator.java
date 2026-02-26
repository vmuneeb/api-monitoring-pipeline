package com.harness.pipeline.pipeline.batch;

import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.notification.NotificationService;
import com.harness.pipeline.service.RuleService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BatchRuleEvaluator {

  private static final Logger log = LoggerFactory.getLogger(BatchRuleEvaluator.class);

  private final RuleService ruleService;
  private final BatchRuleQueryBuilder queryBuilder;
  private final NotificationService notificationService;
  private final String basePath;

  public BatchRuleEvaluator(RuleService ruleService,
                            BatchRuleQueryBuilder queryBuilder,
                            NotificationService notificationService,
                            @Value("${pipeline.batch.base-path:/tmp/api-event-pipeline/batch-events}") String basePath) {
    this.ruleService = ruleService;
    this.queryBuilder = queryBuilder;
    this.notificationService = notificationService;
    this.basePath = basePath;
  }

  public void evaluateAllBatchRules() {
    List<RuleDto> batchRules = ruleService.listAllEnabledByType(RuleType.BATCH);
    if (batchRules.isEmpty()) {
      log.debug("No enabled BATCH rules found");
      return;
    }

    Map<String, List<RuleDto>> rulesByTenant = batchRules.stream()
        .collect(Collectors.groupingBy(RuleDto::tenantId));

    for (Map.Entry<String, List<RuleDto>> entry : rulesByTenant.entrySet()) {
      String tenantId = entry.getKey();
      List<RuleDto> rules = entry.getValue();
      evaluateRulesForTenant(tenantId, rules);
    }
  }

  private void evaluateRulesForTenant(String tenantId, List<RuleDto> rules) {
    Path tenantDir = Path.of(basePath, "tenant_id=" + tenantId);
    if (!Files.exists(tenantDir)) {
      log.debug("No batch data directory for tenant {}", tenantId);
      return;
    }

    for (RuleDto rule : rules) {
      try {
        evaluateSingleRule(rule, tenantDir);
      } catch (Exception e) {
        log.error("Failed to evaluate batch rule {} for tenant {}", rule.name(), tenantId, e);
      }
    }
  }

  private void evaluateSingleRule(RuleDto rule, Path tenantDir) throws Exception {
    int windowMinutes = rule.windowMinutes() != null ? rule.windowMinutes() : 60;
    long threshold = rule.countThreshold() != null ? rule.countThreshold() : 1;
    Instant windowStart = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);

    String fileGlob = buildPartitionGlob(tenantDir, windowStart);
    if (fileGlob == null) {
      log.debug("No matching partition directories for rule '{}'", rule.name());
      return;
    }

    String sql = queryBuilder.buildCountQuery(rule, fileGlob, windowStart);
    log.debug("Batch rule '{}' SQL: {}", rule.name(), sql);

    long count = executeCountQuery(sql);
    log.info("Batch rule '{}': count={}, threshold={}", rule.name(), count, threshold);

    if (count >= threshold) {
      notificationService.notifyBatchThresholdBreached(rule, count);
    }
  }

  /**
   * Build a glob that only covers date partitions within the time window,
   * so DuckDB doesn't scan every file for the tenant.
   */
  String buildPartitionGlob(Path tenantDir, Instant windowStart) {
    LocalDate startDate = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate endDate = LocalDate.now(ZoneOffset.UTC);

    List<String> globs = new ArrayList<>();
    for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
      Path dayDir = tenantDir.resolve(String.format(
          "year=%04d/month=%02d/day=%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth()));
      if (Files.exists(dayDir)) {
        globs.add(dayDir + "/*.jsonl");
      }
    }

    if (globs.isEmpty()) {
      return null;
    }
    return String.join(",", globs);
  }

  long executeCountQuery(String sql) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {
      if (rs.next()) {
        return rs.getLong(1);
      }
      return 0;
    }
  }
}
