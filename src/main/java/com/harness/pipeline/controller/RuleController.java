package com.harness.pipeline.controller;

import com.harness.pipeline.enums.RuleType;
import com.harness.pipeline.model.RuleDto;
import com.harness.pipeline.service.RuleService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/rules")
public class RuleController {

  private final RuleService ruleService;

  public RuleController(RuleService ruleService) {
    this.ruleService = ruleService;
  }

  @PostMapping
  public ResponseEntity<RuleDto> createRule(
      @PathVariable String tenantId,
      @RequestBody RuleDto request) {
    RuleDto created = ruleService.createRule(tenantId, request);
    return ResponseEntity
        .created(URI.create("/api/v1/tenants/" + tenantId + "/rules/" + created.id()))
        .body(created);
  }

  @GetMapping
  public ResponseEntity<List<RuleDto>> listRules(
      @PathVariable String tenantId,
      @RequestParam(value = "type", required = false) RuleType type,
      @RequestParam(value = "enabled", required = false) Boolean enabled) {
    return ResponseEntity.ok(ruleService.listRules(tenantId, type, enabled));
  }

  @GetMapping("/{ruleId}")
  public ResponseEntity<RuleDto> getRule(
      @PathVariable String tenantId,
      @PathVariable UUID ruleId) {
    return ruleService.getRule(tenantId, ruleId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PutMapping("/{ruleId}")
  public ResponseEntity<RuleDto> updateRule(
      @PathVariable String tenantId,
      @PathVariable UUID ruleId,
      @RequestBody RuleDto request) {
    return ruleService.updateRule(tenantId, ruleId, request)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/{ruleId}")
  public ResponseEntity<Void> deleteRule(
      @PathVariable String tenantId,
      @PathVariable UUID ruleId) {
    boolean deleted = ruleService.deleteRule(tenantId, ruleId);
    return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  @PatchMapping("/{ruleId}/enable")
  public ResponseEntity<RuleDto> enableRule(
      @PathVariable String tenantId,
      @PathVariable UUID ruleId) {
    return ruleService.setRuleEnabled(tenantId, ruleId, true)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PatchMapping("/{ruleId}/disable")
  public ResponseEntity<RuleDto> disableRule(
      @PathVariable String tenantId,
      @PathVariable UUID ruleId) {
    return ruleService.setRuleEnabled(tenantId, ruleId, false)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}

