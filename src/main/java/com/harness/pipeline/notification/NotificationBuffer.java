package com.harness.pipeline.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class NotificationBuffer {

  private static final Logger log = LoggerFactory.getLogger(NotificationBuffer.class);
  private static final int MAX_SIZE = 100;

  private final Deque<NotificationRecord> buffer = new ConcurrentLinkedDeque<>();
  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
  private final ObjectMapper objectMapper;

  public NotificationBuffer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void push(NotificationRecord record) {
    buffer.addFirst(record);
    while (buffer.size() > MAX_SIZE) {
      buffer.removeLast();
    }
    broadcast(record);
  }

  public List<NotificationRecord> getRecent() {
    return new ArrayList<>(buffer);
  }

  public SseEmitter subscribe() {
    SseEmitter emitter = new SseEmitter(0L);
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    return emitter;
  }

  private void broadcast(NotificationRecord record) {
    String json;
    try {
      json = objectMapper.writeValueAsString(record);
    } catch (IOException e) {
      log.error("Failed to serialize notification", e);
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("notification").data(json));
      } catch (Exception e) {
        emitters.remove(emitter);
      }
    }
  }
}
