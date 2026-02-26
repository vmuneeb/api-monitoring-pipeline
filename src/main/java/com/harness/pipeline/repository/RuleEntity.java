package com.harness.pipeline.repository;

import com.harness.pipeline.enums.ConditionGroupOperator;
import com.harness.pipeline.enums.RuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rules")
public class RuleEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "name", nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 16)
  private RuleType type;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Enumerated(EnumType.STRING)
  @Column(name = "group_operator", nullable = false, length = 8)
  private ConditionGroupOperator groupOperator;

  @Lob
  @Column(name = "conditions_json", nullable = false)
  @Convert(converter = RuleJsonConverter.class)
  private String conditionsJson;

  @Lob
  @Column(name = "notification_json")
  private String notificationJson;

  @Column(name = "window_minutes")
  private Integer windowMinutes;

  @Column(name = "count_threshold")
  private Long countThreshold;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public RuleEntity() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public RuleType getType() {
    return type;
  }

  public void setType(RuleType type) {
    this.type = type;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public ConditionGroupOperator getGroupOperator() {
    return groupOperator;
  }

  public void setGroupOperator(ConditionGroupOperator groupOperator) {
    this.groupOperator = groupOperator;
  }

  public String getConditionsJson() {
    return conditionsJson;
  }

  public void setConditionsJson(String conditionsJson) {
    this.conditionsJson = conditionsJson;
  }

  public String getNotificationJson() {
    return notificationJson;
  }

  public void setNotificationJson(String notificationJson) {
    this.notificationJson = notificationJson;
  }

  public Integer getWindowMinutes() {
    return windowMinutes;
  }

  public void setWindowMinutes(Integer windowMinutes) {
    this.windowMinutes = windowMinutes;
  }

  public Long getCountThreshold() {
    return countThreshold;
  }

  public void setCountThreshold(Long countThreshold) {
    this.countThreshold = countThreshold;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}

