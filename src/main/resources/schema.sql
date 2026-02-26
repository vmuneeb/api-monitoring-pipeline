CREATE TABLE IF NOT EXISTS rules (
  id UUID PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL,
  group_operator VARCHAR(8) NOT NULL,
  conditions_json CLOB NOT NULL,
  notification_json CLOB,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

