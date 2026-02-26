package com.harness.pipeline.pipeline.realtime;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.enums.RuleOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.ConditionDto;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.NotificationConfigDto;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.notification.NotificationService;
import com.harness.pipeline.pipeline.queue.InMemoryEventBus;
import com.harness.pipeline.service.RuleService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class RealtimeWorkerTest {

  @Test
  void workerEvaluatesRulesAndSendsNotifications() throws Exception {
    InMemoryEventBus bus = new InMemoryEventBus(10, 10);
    RuleService ruleService = mock(RuleService.class);
    NotificationService notificationService = mock(NotificationService.class);

    RealtimeWorker worker = new RealtimeWorker(bus, ruleService, notificationService);
    Thread thread = new Thread(worker);
    thread.start();

    ApiEvent.HttpRequest request = new ApiEvent.HttpRequest(
        "GET",
        "api.example.com",
        "/api/payments",
        null,
        Map.of(),
        null,
        null
    );
    ApiEvent.HttpResponse response = new ApiEvent.HttpResponse(
        500,
        "5xx",
        200L,
        Map.of(),
        null,
        null
    );
    ApiEvent.ServiceMetadata metadata = new ApiEvent.ServiceMetadata(
        "svc-1",
        "payment-svc",
        "prod",
        "us-east-1",
        null,
        null,
        Map.of()
    );
    ApiEvent event = new ApiEvent(
        UUID.randomUUID().toString(),
        "tenant-1",
        Instant.now(),
        Instant.now(),
        request,
        response,
        metadata
    );

    ConditionDto condition = new ConditionDto(
        RuleConditionField.RESPONSE_STATUS_CODE,
        RuleOperator.GREATER_THAN_OR_EQUAL,
        "500"
    );
    ConditionGroupDto group = new ConditionGroupDto(
        ConditionGroupOperator.AND,
        List.of(condition)
    );
    RuleDto rule = new RuleDto(
        UUID.randomUUID(),
        "tenant-1",
        "Any 5xx",
        RuleType.REALTIME,
        true,
        ConditionGroupOperator.AND,
        List.of(group),
        new NotificationConfigDto("LOG", "console"),
        Instant.now(),
        Instant.now()
    );

    given(ruleService.listRules(eq("tenant-1"), eq(RuleType.REALTIME), eq(true)))
        .willReturn(List.of(rule));

    bus.publish(event);

    ArgumentCaptor<ApiEvent> eventCaptor = ArgumentCaptor.forClass(ApiEvent.class);
    ArgumentCaptor<RuleDto> ruleCaptor = ArgumentCaptor.forClass(RuleDto.class);
    verify(notificationService, timeout(1000))
        .notify(eventCaptor.capture(), ruleCaptor.capture());

    assertThat(eventCaptor.getValue().tenantId()).isEqualTo("tenant-1");
    assertThat(ruleCaptor.getValue().name()).isEqualTo("Any 5xx");

    worker.shutdown();
    thread.interrupt();
  }
}

