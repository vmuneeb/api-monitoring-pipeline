package com.harness.pipeline.service;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.NotificationConfigDto;
import com.harness.pipeline.model.RuleDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RuleServiceIntegrationTest {

  @Autowired
  private RuleService ruleService;

  @Test
  void createAndListRulesByTenantTypeAndEnabled() {
    String tenantA = "tenant-a";
    String tenantB = "tenant-b";

    RuleDto realtimeEnabled = ruleService.createRule(
        tenantA,
        newRuleRequest(
            "Realtime 5xx",
            RuleType.REALTIME,
            true,
            ConditionGroupOperator.AND
        )
    );

    RuleDto realtimeDisabled = ruleService.createRule(
        tenantA,
        newRuleRequest(
            "Realtime 4xx",
            RuleType.REALTIME,
            false,
            ConditionGroupOperator.OR
        )
    );

    RuleDto batchEnabled = ruleService.createRule(
        tenantA,
        newRuleRequest(
            "Batch error rate",
            RuleType.BATCH,
            true,
            ConditionGroupOperator.AND
        )
    );

    ruleService.createRule(
        tenantB,
        newRuleRequest(
            "Other tenant rule",
            RuleType.REALTIME,
            true,
            ConditionGroupOperator.AND
        )
    );

    // When
    var allTenantARules = ruleService.listRules(tenantA, null, null);
    var tenantARealtimeEnabled =
        ruleService.listRules(tenantA, RuleType.REALTIME, true);
    var tenantABatchAny =
        ruleService.listRules(tenantA, RuleType.BATCH, null);
    var tenantBRealtimeEnabled =
        ruleService.listRules(tenantB, RuleType.REALTIME, true);

    // Then
    assertThat(allTenantARules)
        .extracting(RuleDto::id)
        .containsExactlyInAnyOrder(
            realtimeEnabled.id(),
            realtimeDisabled.id(),
            batchEnabled.id()
        );

    assertThat(tenantARealtimeEnabled)
        .singleElement()
        .extracting(RuleDto::id)
        .isEqualTo(realtimeEnabled.id());

    assertThat(tenantABatchAny)
        .singleElement()
        .extracting(RuleDto::id)
        .isEqualTo(batchEnabled.id());

    assertThat(tenantBRealtimeEnabled)
        .hasSize(1);
  }

  @Test
  void getUpdateEnableDisableAndDeleteRule() {
    String tenantId = "tenant-test";

    RuleDto created = ruleService.createRule(
        tenantId,
        newRuleRequest(
            "Initial name",
            RuleType.REALTIME,
            true,
            ConditionGroupOperator.AND
        )
    );

    Optional<RuleDto> loaded = ruleService.getRule(tenantId, created.id());
    assertThat(loaded).isPresent();
    assertThat(loaded.get().name()).isEqualTo("Initial name");

    RuleDto updateRequest = new RuleDto(
        created.id(),
        tenantId,
        "Updated name",
        RuleType.BATCH,
        false,
        ConditionGroupOperator.OR,
        created.conditionGroups(),
        created.notificationConfig(),
        null,
        null,
        created.createdAt(),
        created.updatedAt()
    );

    Optional<RuleDto> updated =
        ruleService.updateRule(tenantId, created.id(), updateRequest);

    assertThat(updated).isPresent();
    assertThat(updated.get().name()).isEqualTo("Updated name");
    assertThat(updated.get().type()).isEqualTo(RuleType.BATCH);
    assertThat(updated.get().enabled()).isFalse();
    assertThat(updated.get().groupOperator()).isEqualTo(ConditionGroupOperator.OR);

    Optional<RuleDto> enabled =
        ruleService.setRuleEnabled(tenantId, created.id(), true);
    assertThat(enabled).isPresent();
    assertThat(enabled.get().enabled()).isTrue();

    boolean deleted = ruleService.deleteRule(tenantId, created.id());
    assertThat(deleted).isTrue();

    Optional<RuleDto> afterDelete = ruleService.getRule(tenantId, created.id());
    assertThat(afterDelete).isEmpty();
  }

  @Test
  void operationsWithWrongTenantIdReturnEmptyOrFalse() {
    String tenantId = "tenant-one";

    RuleDto created = ruleService.createRule(
        tenantId,
        newRuleRequest(
            "Tenant one rule",
            RuleType.REALTIME,
            true,
            ConditionGroupOperator.AND
        )
    );

    Optional<RuleDto> wrongTenantGet =
        ruleService.getRule("tenant-two", created.id());
    assertThat(wrongTenantGet).isEmpty();

    Optional<RuleDto> wrongTenantUpdate =
        ruleService.updateRule("tenant-two", created.id(), created);
    assertThat(wrongTenantUpdate).isEmpty();

    Optional<RuleDto> wrongTenantEnable =
        ruleService.setRuleEnabled("tenant-two", created.id(), false);
    assertThat(wrongTenantEnable).isEmpty();

    boolean wrongTenantDelete =
        ruleService.deleteRule("tenant-two", created.id());
    assertThat(wrongTenantDelete).isFalse();
  }

  private RuleDto newRuleRequest(
      String name,
      RuleType type,
      boolean enabled,
      ConditionGroupOperator groupOperator
  ) {
    ConditionDto condition = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CODE,
        RuleOperator.GREATER_THAN_OR_EQUAL,
        "500"
    );

    ConditionGroupDto group = new ConditionGroupDto(
        groupOperator,
        List.of(condition)
    );

    NotificationConfigDto notification = new NotificationConfigDto(
        "LOG",
        "console"
    );

    return new RuleDto(
        null,
        null,
        name,
        type,
        enabled,
        groupOperator,
        List.of(group),
        notification,
        null,
        null,
        null,
        null
    );
  }
}

