# API Event Processing Pipeline — Design Document

## Overview

Multi-tenant SaaS pipeline that ingests HTTP API events, evaluates tenant-configured
alerting rules, and dispatches notifications. Two processing modes: **realtime**
(per-event) and **batch** (aggregate over time windows using DuckDB).

Built as a single runnable Spring Boot JAR. Every infrastructure dependency is behind
a clean interface so production components (Kafka, S3, Spark) can be swapped without
changing pipeline logic.

---

## Project Structure

```
api-event-pipeline/
├── pom.xml
├── README.md
├── PLAN.md
├── HOW_TO_TEST.md
├── .github/workflows/ci.yml
│
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── application.yml
    │   │   ├── schema.sql
    │   │   └── static/index.html          # Live notification dashboard
    │   │
    │   └── java/com/harness/pipeline/
    │       ├── ApiEventPipelineApplication.java
    │       ├── enums/                      # RuleType, RuleConditionField, RuleOperator, ConditionGroupOperator
    │       ├── model/                      # ApiEvent, ApiEventRequest, RuleDto, ConditionDto, ConditionGroupDto
    │       ├── repository/                 # RuleEntity, RuleRepository (Spring Data JPA)
    │       ├── service/                    # RuleService, EventIngestionService
    │       ├── controller/                 # EventIngestionController, RuleController, NotificationController
    │       ├── ruleengine/evaluator/       # EventFieldExtractor, ConditionEvaluator, RealtimeRuleEvaluator
    │       ├── pipeline/
    │       │   ├── queue/                  # EventBus (interface), InMemoryEventBus
    │       │   ├── realtime/              # RealtimeWorker, RealtimeWorkerPool
    │       │   └── batch/                 # BatchWriter, BatchScheduler, BatchRuleQueryBuilder, BatchRuleEvaluator, BatchAggregationScheduler
    │       └── notification/              # NotificationService (interface), LoggingNotificationService, NotificationBuffer, NotificationRecord
    │
    └── test/java/com/harness/pipeline/
        ├── ApiEventPipelineApplicationTest.java
        ├── controller/                     # EventIngestionControllerTest
        ├── service/                        # RuleServiceIntegrationTest
        ├── ruleengine/                     # ConditionEvaluatorTest, RealtimeRuleEvaluatorTest
        └── pipeline/
            ├── queue/                      # InMemoryEventBusTest
            ├── realtime/                   # RealtimeWorkerTest
            └── batch/                      # BatchWriterTest, BatchRuleQueryBuilderTest, BatchRuleEvaluatorTest
```

---

## Domain Model

### ApiEvent

Represents one HTTP request/response cycle captured by a tenant's agent.

```
ApiEvent
  ├── eventId         String (UUID)
  ├── tenantId        String
  ├── timestamp       Instant (client-side)
  ├── receivedAt      Instant (server-side)
  ├── request         { method, host, path, queryString, headers, body, sizeBytes }
  ├── response        { statusCode, statusClass, responseTimeMs, headers, body, sizeBytes }
  └── metadata        { serviceId, serviceName, environment, region, hostIp, traceId, tags }
```

### Rule Model

Both REALTIME and BATCH rules use the same two-level AND/OR condition structure:

```
Rule
  ├── type              REALTIME | BATCH
  ├── groupOperator     AND | OR              ← how groups combine
  ├── conditionGroups[]
  │     ├── operator    AND | OR              ← how conditions combine within group
  │     └── conditions[]
  │           ├── field     RuleConditionField
  │           ├── operator  RuleOperator
  │           └── value     String
  ├── windowMinutes     Integer (BATCH only)  ← time window for aggregation
  └── countThreshold    Long (BATCH only)     ← COUNT(*) threshold to trigger alert
```

---

## Sample Rules

**Realtime — slow payment errors in prod:**
```json
{
  "name": "Slow payment errors in prod",
  "type": "REALTIME",
  "enabled": true,
  "groupOperator": "AND",
  "conditionGroups": [
    {
      "operator": "AND",
      "conditions": [
        { "field": "REQUEST_METHOD", "operator": "EQUALS", "value": "POST" },
        { "field": "REQUEST_PATH", "operator": "STARTS_WITH", "value": "/api/payment" }
      ]
    },
    {
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500" },
        { "field": "RESPONSE_TIME_MS", "operator": "GREATER_THAN", "value": "1000" }
      ]
    },
    {
      "operator": "AND",
      "conditions": [
        { "field": "METADATA_ENVIRONMENT", "operator": "EQUALS", "value": "prod" }
      ]
    }
  ],
  "notificationConfig": { "channel": "LOG", "destination": "console" }
}
```

**Realtime — any error in US regions OR any 503 anywhere:**
```json
{
  "name": "US errors or 503 anywhere",
  "type": "REALTIME",
  "enabled": true,
  "groupOperator": "OR",
  "conditionGroups": [
    {
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500" },
        { "field": "METADATA_REGION", "operator": "STARTS_WITH", "value": "us-" }
      ]
    },
    {
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_STATUS_CODE", "operator": "EQUALS", "value": "503" }
      ]
    }
  ],
  "notificationConfig": { "channel": "LOG", "destination": "console" }
}
```

**Batch — more than 50 failed health checks in 30 minutes:**
```json
{
  "name": "Health endpoint failure spike",
  "type": "BATCH",
  "enabled": true,
  "groupOperator": "AND",
  "conditionGroups": [{
    "operator": "AND",
    "conditions": [
      { "field": "REQUEST_PATH", "operator": "CONTAINS", "value": "/health" },
      { "field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500" }
    ]
  }],
  "notificationConfig": { "channel": "LOG", "destination": "console" },
  "windowMinutes": 30,
  "countThreshold": 50
}
```

The batch rule translates into a DuckDB query:
```sql
SELECT COUNT(*) FROM read_json_auto('.../tenant_id=abc/**/*.jsonl')
WHERE received_at >= {30min ago}
  AND (request_path LIKE '%/health%' AND status_code >= 500)
```

---

## Realtime Pipeline

```
EventIngestionController
  → EventIngestionService (maps request → ApiEvent, publishes to EventBus)
    → EventBus (realtime queue: offer/drop if full, batch queue: put/block)
      → RealtimeWorkerPool (N threads)
        → RealtimeWorker: dequeue → fetch rules → evaluate → notify if matched
```

The rule evaluator applies two-level AND/OR logic:
- For each condition group, evaluate all conditions and combine with the group's operator
- Combine all group results with the rule's top-level `groupOperator`

---

## Batch Pipeline

```
EventBus (batch queue)
  → BatchScheduler (scheduled flush)
    → BatchWriter: drain queue → write JSONL files partitioned by tenant/date

BatchAggregationScheduler (separate schedule)
  → BatchRuleEvaluator: for each tenant with BATCH rules
    → BatchRuleQueryBuilder: translate conditions → DuckDB SQL
    → DuckDB: execute COUNT(*) over JSONL files
    → compare count against threshold → notify if breached
```

**Partition layout (Hive-style):**
```
{basePath}/tenant_id={tenantId}/year=YYYY/month=MM/day=DD/events-{ts}-{uuid}.jsonl
```

**JSONL schema (flat columns written by BatchWriter):**
```
event_id, tenant_id, timestamp, received_at,
http_method, request_host, request_path, query_string,
status_code, status_class, response_time_ms,
environment, region, service_id, trace_id
```

---

## Notification Dashboard

A single-page static UI served at `http://localhost:8080` using Server-Sent Events (SSE).

- `NotificationBuffer` — in-memory ring buffer (last 100 notifications) with SSE broadcasting
- `NotificationController` — `GET /api/v1/notifications` (history) and `GET /api/v1/notifications/stream` (SSE)
- Both realtime and batch notifications appear live, color-coded by type

---

## Configuration

All configurable via `application.yml` or command-line overrides:

```yaml
pipeline:
  queue:
    realtime-capacity: 10000          # offer() drops if full
    batch-capacity: 50000             # put() blocks if full
  batch:
    base-path: /tmp/api-event-pipeline/batch-events
    flush-interval-ms: 600000         # how often to write JSONL
    aggregation-interval-ms: 300000   # how often to run batch rules
```
