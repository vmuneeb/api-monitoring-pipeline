package com.harness.pipeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.ConditionGroupDto;
import com.harness.pipeline.model.NotificationConfigDto;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.repository.RuleEntity;
import com.harness.pipeline.repository.RuleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleService {

  private final RuleRepository repository;
  private final ObjectMapper objectMapper;

  public RuleService(RuleRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public RuleDto createRule(String tenantId, RuleDto request) {
    Instant now = Instant.now();
    RuleEntity entity = new RuleEntity();
    entity.setId(UUID.randomUUID());
    entity.setTenantId(tenantId);
    entity.setName(request.name());
    entity.setType(request.type());
    entity.setEnabled(request.enabled());
    entity.setGroupOperator(request.groupOperator());
    entity.setConditionsJson(serializeConditions(request.conditionGroups()));
    entity.setNotificationJson(serializeNotification(request.notificationConfig()));
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    RuleEntity saved = repository.save(entity);
    return toDto(saved);
  }

  @Transactional(readOnly = true)
  public List<RuleDto> listRules(String tenantId, RuleType type, Boolean enabled) {
    List<RuleEntity> entities;
    if (type != null && enabled != null) {
      entities = repository.findByTenantIdAndTypeAndEnabled(tenantId, type, enabled);
    } else if (type != null) {
      entities = repository.findByTenantIdAndType(tenantId, type);
    } else {
      entities = repository.findByTenantId(tenantId);
    }
    return entities.stream().map(this::toDto).toList();
  }

  @Transactional(readOnly = true)
  public Optional<RuleDto> getRule(String tenantId, UUID ruleId) {
    return repository.findByIdAndTenantId(ruleId, tenantId).map(this::toDto);
  }

  @Transactional
  public Optional<RuleDto> updateRule(String tenantId, UUID ruleId, RuleDto request) {
    return repository.findByIdAndTenantId(ruleId, tenantId).map(existing -> {
      existing.setName(request.name());
      existing.setType(request.type());
      existing.setEnabled(request.enabled());
      existing.setGroupOperator(
          request.groupOperator() != null ? request.groupOperator() : ConditionGroupOperator.AND);
      existing.setConditionsJson(serializeConditions(request.conditionGroups()));
      existing.setNotificationJson(serializeNotification(request.notificationConfig()));
      existing.setUpdatedAt(Instant.now());
      return toDto(repository.save(existing));
    });
  }

  @Transactional
  public boolean deleteRule(String tenantId, UUID ruleId) {
    return repository.findByIdAndTenantId(ruleId, tenantId)
        .map(entity -> {
          repository.delete(entity);
          return true;
        })
        .orElse(false);
  }

  @Transactional
  public Optional<RuleDto> setRuleEnabled(String tenantId, UUID ruleId, boolean enabled) {
    return repository.findByIdAndTenantId(ruleId, tenantId).map(entity -> {
      entity.setEnabled(enabled);
      entity.setUpdatedAt(Instant.now());
      return toDto(repository.save(entity));
    });
  }

  private String serializeConditions(List<ConditionGroupDto> groups) {
    try {
      return objectMapper.writeValueAsString(groups);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize condition groups", e);
    }
  }

  private String serializeNotification(NotificationConfigDto config) {
    if (config == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize notification config", e);
    }
  }

  private List<ConditionGroupDto> deserializeConditions(String json) {
    try {
      return objectMapper.readValue(
          json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, ConditionGroupDto.class)
      );
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize condition groups", e);
    }
  }

  private NotificationConfigDto deserializeNotification(String json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, NotificationConfigDto.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize notification config", e);
    }
  }

  private RuleDto toDto(RuleEntity entity) {
    return new RuleDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getName(),
        entity.getType(),
        entity.isEnabled(),
        entity.getGroupOperator(),
        deserializeConditions(entity.getConditionsJson()),
        deserializeNotification(entity.getNotificationJson()),
        entity.getCreatedAt(),
        entity.getUpdatedAt()
    );
  }
}

