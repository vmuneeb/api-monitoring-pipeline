package com.harness.pipeline.controller;

import com.harness.pipeline.notification.NotificationBuffer;
import com.harness.pipeline.notification.NotificationRecord;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationBuffer buffer;

  public NotificationController(NotificationBuffer buffer) {
    this.buffer = buffer;
  }

  @GetMapping
  public ResponseEntity<List<NotificationRecord>> getRecent() {
    return ResponseEntity.ok(buffer.getRecent());
  }

  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return buffer.subscribe();
  }
}
