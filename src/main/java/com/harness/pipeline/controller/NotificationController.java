package com.harness.pipeline.controller;

import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.notification.NotificationBuffer;
import com.harness.pipeline.notification.NotificationRecord;
import com.harness.pipeline.service.RuleService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {

  private final NotificationBuffer buffer;
  private final RuleService ruleService;

  public NotificationController(NotificationBuffer buffer, RuleService ruleService) {
    this.buffer = buffer;
    this.ruleService = ruleService;
  }

  @GetMapping("/notifications")
  public ResponseEntity<List<NotificationRecord>> getRecent() {
    return ResponseEntity.ok(buffer.getRecent());
  }

  @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return buffer.subscribe();
  }

  @GetMapping("/rules")
  public ResponseEntity<List<RuleDto>> listAllRules() {
    return ResponseEntity.ok(ruleService.listAllRules());
  }
}
