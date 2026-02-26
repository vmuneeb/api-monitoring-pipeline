package com.harness.pipeline.repository;

import com.harness.pipeline.enums.RuleType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleRepository extends JpaRepository<RuleEntity, UUID> {

  List<RuleEntity> findByTenantId(String tenantId);

  List<RuleEntity> findByTenantIdAndType(String tenantId, RuleType type);

  List<RuleEntity> findByTenantIdAndTypeAndEnabled(String tenantId, RuleType type, boolean enabled);

  Optional<RuleEntity> findByIdAndTenantId(UUID id, String tenantId);

  List<RuleEntity> findByTypeAndEnabled(RuleType type, boolean enabled);
}

