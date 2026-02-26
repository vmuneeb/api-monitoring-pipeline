# API Event Processing Pipeline — Project Plan

## Overview

Multi-tenant SaaS pipeline that ingests HTTP API request/response events, evaluates
tenant-configured alerting rules, and dispatches notifications. Two processing modes:
realtime (per-event) and batch (aggregate over time windows).

Built as a single runnable JAR. Every infrastructure dependency is mocked behind a
clean interface so production components (Kafka, S3, Spark/Iceberg) can be swapped
in via configuration without changing pipeline logic.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Module Structure](#module-structure)
3. [Domain Model](#domain-model)
4. [Rule Model — AND / OR Logic](#rule-model--and--or-logic)
5. [Abstractions & Mock Implementations](#abstractions--mock-implementations)
6. [Realtime Pipeline](#realtime-pipeline)
7. [Batch Pipeline](#batch-pipeline)
8. [Rule Engine](#rule-engine)
9. [Catalog (H2)](#catalog-h2)
10. [Rule Management API](#rule-management-api)
11. [Ingestion API](#ingestion-api)
12. [Notification Service](#notification-service)
13. [Pipeline Simulation](#pipeline-simulation)
14. [Test Plan](#test-plan)
15. [Running the Project](#running-the-project)
16. [Configuration Reference](#configuration-reference)
17. [Assumptions & Trade-offs](#assumptions--trade-offs)
18. [V2 Improvements](#v2-improvements)

---

## Architecture

```
                        ┌─────────────────────────────────────────────────┐
                        │              Tenant (HTTP Client)                │
                        │  POST /api/v1/events  (JSON + X-Tenant-Id)      │
                        └───────────────────┬─────────────────────────────┘
                                            │
                                            ▼
                        ┌─────────────────────────────────────────────────┐
                        │           EventIngestionController               │
                        │  • validates request                             │
                        │  • stamps receivedAt                             │
                        │  • maps JSON → ApiEvent domain object            │
                        │  • serializes to bytes (proto in prod)           │
                        │  • returns 202 Accepted (async from here)        │
                        └───────────────────┬─────────────────────────────┘
                                            │
                                            ▼
                        ┌─────────────────────────────────────────────────┐
                        │                 EventRouter                      │
                        │         [Interface: EventBus]                    │
                        │                                                  │
                        │  Mock: LinkedBlockingQueue (two queues)          │
                        │  Prod: Kafka topic + consumer groups             │
                        └──────────┬──────────────────┬───────────────────┘
                                   │                  │
                    offer()        │                  │  put()
                 (drop if full)    │                  │  (block — never drop)
                                   │                  │
               ┌───────────────────▼──┐  ┌───────────▼──────────────────┐
               │   RealtimeWorker(s)  │  │       BatchWriter             │
               │  [N threads]         │  │  accumulates events in memory │
               │                      │  │  flushes to Parquet on disk   │
               │  1. dequeue bytes    │  │  every 30s or 100 events      │
               │  2. deserialize      │  │                               │
               │  3. fetch rules      │  │  [Interface: EventStore]      │
               │     (cached 30s)     │  │  Mock: local filesystem       │
               │  4. evaluate rules   │  │  Prod: S3Client               │
               │  5. notify if fired  │  └───────────┬──────────────────┘
               └──────────┬───────────┘              │
                          │                          │  Parquet files on disk
                          │                          │  /tmp/event-store/
                          │                          │    tenant_id=abc/
                          │                          │      year=2026/month=02/day=26/
                          │                          │        events-123.parquet
                          │                          │
                          │               ┌──────────▼──────────────────┐
                          │               │    BatchScheduler (@cron)    │
                          │               │                              │
                          │               │  for each tenant:            │
                          │               │    for each BATCH rule:      │
                          │               │      1. get window from rule  │
                          │               │      2. get path from catalog │
                          │               │      3. run DuckDB query      │
                          │               │      4. evaluate rule         │
                          │               │      5. notify if fired       │
                          │               │                              │
                          │               │  [Interface: AggregateComputer]
                          │               │  Mock: DuckDB                │
                          │               │  Prod: Spark on EMR          │
                          │               └──────────┬──────────────────┘
                          │                          │
               ┌──────────▼──────────────────────────▼──────────────────┐
               │                 NotificationService                      │
               │            [Interface: NotificationDispatcher]           │
               │                                                          │
               │  Mock: LOG to console + persist to H2                   │
               │  Prod: Slack API / Email (SES) / Webhook HTTP call       │
               └─────────────────────────────────────────────────────────┘

                        ┌─────────────────────────────────────────────────┐
                        │               H2 Database (Catalog)              │
                        │                                                  │
                        │  rules                — tenant rule config       │
                        │  tenant_data_location — where parquet files are  │
                        │  tenant_schema_column — queryable column schema  │
                        │  notification_log     — audit trail              │
                        │                                                  │
                        │  Mock: H2 in-memory                             │
                        │  Prod: PostgreSQL / Aurora                       │
                        └─────────────────────────────────────────────────┘
```

---

## Module Structure

Single Maven project — one JAR, all components in one process.
Packages are structured as if they were separate microservices so extraction is easy later.

```
api-event-pipeline/
│
├── pom.xml
├── PLAN.md
├── README.md
│
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── application.yml          # all config, profiles for mock vs prod
    │   │   └── schema.sql               # H2 DDL
    │   │
    │   └── java/com/harness/pipeline/
    │       │
    │       ├── ApiEventPipelineApplication.java   # main() + ApplicationRunner
    │       │
    │       ├── enums/
    │       │   ├── RuleType.java                  # REALTIME | BATCH
    │       │   ├── RuleConditionField.java         # REQUEST_* | RESPONSE_* | AGGREGATE_*
    │       │   ├── RuleOperator.java               # EQUALS | GT | CONTAINS | REGEX...
    │       │   └── ConditionGroupOperator.java     # AND | OR  (extensible — V2: NOT, XOR)
    │       │
    │       ├── model/
    │       │   ├── ApiEvent.java                  # HttpRequest + HttpResponse + Metadata
    │       │   └── Rule.java                      # ConditionGroup[] + WindowConfig + NotificationConfig
    │       │
    │       ├── controller/
    │       │   ├── EventIngestionController.java  # POST /api/v1/events
    │       │   └── RuleController.java            # CRUD /api/v1/tenants/{id}/rules
    │       │
    │       ├── service/
    │       │   └── RuleService.java               # rule CRUD + cache management
    │       │
    │       ├── repository/
    │       │   └── RuleRepository.java            # Spring Data JPA
    │       │
    │       ├── config/
    │       │   ├── PipelineConfig.java            # @ConfigurationProperties
    │       │   └── JacksonConfig.java             # ObjectMapper with JavaTimeModule
    │       │
    │       ├── pipeline/
    │       │   ├── queue/
    │       │   │   ├── EventBus.java              # INTERFACE — abstracts Kafka / Queue
    │       │   │   └── InMemoryEventBus.java      # MOCK — LinkedBlockingQueue fan-out
    │       │   │
    │       │   ├── realtime/
    │       │   │   ├── RealtimeWorker.java        # Runnable — dequeue → evaluate → notify
    │       │   │   └── RealtimeWorkerPool.java    # manages N worker threads
    │       │   │
    │       │   └── batch/
    │       │       ├── EventStore.java            # INTERFACE — abstracts S3 / filesystem
    │       │       ├── LocalFileEventStore.java   # MOCK — writes Parquet to local disk
    │       │       ├── BatchWriter.java           # accumulates events, flushes to EventStore
    │       │       ├── BatchWorker.java           # Runnable — dequeue → BatchWriter
    │       │       ├── AggregateComputer.java     # INTERFACE — abstracts Spark / DuckDB
    │       │       ├── DuckDBAggregateComputer.java  # MOCK — DuckDB over local Parquet
    │       │       └── BatchScheduler.java        # @Scheduled — runs batch rules per tenant
    │       │
    │       ├── ruleengine/
    │       │   ├── model/
    │       │   │   └── AggregateMetrics.java      # output of DuckDB, input to batch evaluator
    │       │   └── evaluator/
    │       │       ├── EventFieldExtractor.java   # ApiEvent field → String value
    │       │       ├── ConditionEvaluator.java    # String value + operator + expected → boolean
    │       │       ├── RuleEvaluator.java         # INTERFACE — shared contract for both pipelines
    │       │       ├── RealtimeRuleEvaluator.java # evaluates REALTIME rules against ApiEvent
    │       │       └── AggregateRuleEvaluator.java # evaluates BATCH rules against AggregateMetrics
    │       │
    │       ├── catalog/
    │       │   ├── model/
    │       │   │   ├── TenantDataLocation.java    # @Entity — where parquet files live
    │       │   │   └── TenantSchemaColumn.java    # @Entity — queryable columns per tenant
    │       │   ├── repository/
    │       │   │   ├── TenantDataLocationRepository.java
    │       │   │   └── TenantSchemaColumnRepository.java
    │       │   └── service/
    │       │       └── TenantCatalogService.java  # register, recordWrite, getGlob, getSchema
    │       │
    │       ├── notification/
    │       │   ├── model/
    │       │   │   └── NotificationRecord.java    # @Entity — persisted to H2
    │       │   └── service/
    │       │       ├── NotificationDispatcher.java         # INTERFACE
    │       │       └── LoggingNotificationDispatcher.java  # MOCK — logs + H2 audit
    │       │
    │       └── simulation/
    │           └── PipelineSimulator.java         # generates synthetic events on startup
    │
    └── test/
        └── java/com/harness/pipeline/
            ├── ruleengine/
            │   ├── RealtimeRuleEvaluatorTest.java
            │   ├── AggregateRuleEvaluatorTest.java
            │   ├── ConditionEvaluatorTest.java
            │   └── EventFieldExtractorTest.java
            ├── pipeline/
            │   ├── InMemoryEventBusTest.java
            │   └── BatchWriterTest.java
            ├── catalog/
            │   └── TenantCatalogServiceTest.java
            └── integration/
                └── PipelineIntegrationTest.java
```

---

## Domain Model

### ApiEvent

One complete HTTP request/response cycle captured by a tenant's agent/SDK.

```
ApiEvent
  ├── eventId        String     UUID, deduplication key
  ├── tenantId       String     ALL processing scoped by this — never null
  ├── timestamp      Instant    client-side capture time
  ├── receivedAt     Instant    ingestion server stamp
  │                             delta(timestamp, receivedAt) = clock skew indicator
  ├── request        HttpRequest
  │     ├── method         String    GET | POST | PUT | DELETE | PATCH
  │     ├── host           String    api.example.com
  │     ├── path           String    /api/v1/users/123
  │     ├── queryString    String    page=2&limit=10
  │     ├── headers        Map<String,String>
  │     ├── body           String    full body, max 100kb
  │     └── sizeBytes      long
  ├── response       HttpResponse
  │     ├── statusCode     int       200, 404, 500
  │     ├── statusClass    String    "2xx","4xx","5xx"  ← denormalized for fast filtering
  │     ├── responseTimeMs long      end-to-end latency
  │     ├── headers        Map<String,String>
  │     ├── body           String    full body, max 100kb
  │     └── sizeBytes      long
  └── metadata       ServiceMetadata
        ├── serviceId      String
        ├── serviceName    String
        ├── environment    String    prod | staging | dev
        ├── region         String    us-east-1
        ├── hostIp         String    which instance
        ├── traceId        String    links to APM traces
        └── tags           Map<String,String>   {"team":"payments","version":"v2"}
```

**Body storage note:** Full bodies stored up to 100kb. In production a pre-ingestion
agent would strip Authorization/Cookie headers, run DLP scanning, and truncate bodies
with a `truncated=true` flag. Documented here but not implemented in the mock.

---

## Rule Model — AND / OR Logic

Rules are the core tenant-facing configuration. Both REALTIME and BATCH rule types
use the same boolean model — the only difference is which fields they reference.

### The Two Rule Types

```
REALTIME rule                          BATCH rule
─────────────────────────────────────────────────────────────────────
Triggered by:  every individual        Triggered by: @Scheduled cron
               ApiEvent as it arrives               over a time window

Conditions     per-event fields:       Conditions    aggregate fields:
reference:       REQUEST_METHOD          reference:    AGGREGATE_ERROR_RATE_PERCENT
                 RESPONSE_STATUS_CODE                  AGGREGATE_P99_RESPONSE_TIME_MS
                 RESPONSE_TIME_MS                      AGGREGATE_REQUEST_COUNT
                 METADATA_ENVIRONMENT                  AGGREGATE_DISTINCT_PATHS_COUNT
                 REQUEST_BODY                          (computed by DuckDB over Parquet)
                 METADATA_TAG
                 ... (any event field)

Window:        none — per event        Window:       TUMBLING or SLIDING
                                                     durationMinutes required

Evaluation:    inline, synchronous     Evaluation:   async, on schedule
               result: notify or not  result:        notify or not
```

### Condition Group Structure

Both rule types use identical boolean logic — groups of conditions combined with AND/OR:

```
Rule
  ├── type              REALTIME | BATCH
  ├── groupOperator     AND | OR          ← how groups combine with each other
  └── conditionGroups[]
        ├── operator    AND | OR          ← how conditions combine within the group
        └── conditions[]
              ├── field     RuleConditionField
              ├── operator  RuleOperator
              └── value     String
```

### AND / OR Design Decision

`ConditionGroupOperator` is an **enum**, not a boolean. This is intentional:

```java
// Current
public enum ConditionGroupOperator {
    AND,   // all conditions / groups must be true
    OR     // at least one condition / group must be true
}

// V2 additions are additive — no model changes required
// NOT, XOR, NAND would be new enum values + new cases in the evaluator switch
```

The evaluator uses a single switch that is trivially extended:

```java
private boolean applyOperator(ConditionGroupOperator op, List<Boolean> results) {
    return switch (op) {
        case AND -> results.stream().allMatch(b -> b);
        case OR  -> results.stream().anyMatch(b -> b);
        // V2: case NOT -> results.stream().noneMatch(b -> b);
    };
}
```

This same method is called at both levels — within a group and across groups —
keeping the logic DRY. Adding a new operator is: one enum value + one switch case.

### What AND/OR Covers

The two-level group model expresses full boolean algebra for 95% of real alerting needs:

```
# Simple AND — all must match
groupOperator: AND, group[0]: AND [status>=500, path=/payments]
→ status >= 500 AND path starts with /payments

# Simple OR — any path fires
groupOperator: OR
  group[0]: AND [status>=500]
  group[1]: AND [response_time_ms>5000]
→ status >= 500 OR response_time_ms > 5000

# Complex — (A AND B) OR (C AND D)
groupOperator: OR
  group[0]: AND [status>=500, path=/payments]
  group[1]: AND [response_time_ms>5000, env=prod]
→ (status>=500 AND path=/payments) OR (response_time_ms>5000 AND env=prod)

# OR within a group
groupOperator: AND
  group[0]: OR [env=prod, env=staging]   ← either environment
  group[1]: AND [status>=500]
→ (env=prod OR env=staging) AND status>=500
```

### Condition Field Formats

Standard fields: `value` is the raw comparison string.

Map-based fields use `"key=expectedValue"` encoding in the `value` field:
- `REQUEST_HEADER`: `"Content-Type=application/json"` → `headers.get("Content-Type")`
- `RESPONSE_HEADER`: `"X-Error-Code=TIMEOUT"` → `headers.get("X-Error-Code")`
- `METADATA_TAG`: `"team=payments"` → `tags.get("team")`

The key portion is used by `EventFieldExtractor` for the map lookup.
The value portion is compared by `ConditionEvaluator`.

### Rule Examples

**REALTIME — 5xx on payments path:**
```json
{
  "name": "Payment 5xx Alert",
  "type": "REALTIME",
  "enabled": true,
  "groupOperator": "OR",
  "conditionGroups": [{
    "operator": "AND",
    "conditions": [
      { "field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500" },
      { "field": "REQUEST_PATH",         "operator": "STARTS_WITH",           "value": "/api/payments" }
    ]
  }],
  "notificationConfig": {
    "channel": "SLACK",
    "destination": "#alerts-payments"
  }
}
```

**REALTIME — slow response OR any 5xx in prod:**
```json
{
  "name": "Prod Degradation",
  "type": "REALTIME",
  "enabled": true,
  "groupOperator": "OR",
  "conditionGroups": [
    {
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_TIME_MS",      "operator": "GREATER_THAN", "value": "5000" },
        { "field": "METADATA_ENVIRONMENT",  "operator": "EQUALS",       "value": "prod" }
      ]
    },
    {
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_STATUS_CODE",  "operator": "GREATER_THAN_OR_EQUAL", "value": "500" },
        { "field": "METADATA_ENVIRONMENT",  "operator": "EQUALS",                "value": "prod" }
      ]
    }
  ],
  "notificationConfig": {
    "channel": "LOG",
    "destination": "console"
  }
}
```

**BATCH — high error rate over last 60 minutes (min 100 requests):**
```json
{
  "name": "High Error Rate",
  "type": "BATCH",
  "enabled": true,
  "groupOperator": "OR",
  "conditionGroups": [{
    "operator": "AND",
    "conditions": [
      { "field": "AGGREGATE_ERROR_RATE_PERCENT", "operator": "GREATER_THAN_OR_EQUAL", "value": "5.0" },
      { "field": "AGGREGATE_REQUEST_COUNT",      "operator": "GREATER_THAN",           "value": "100" }
    ]
  }],
  "windowConfig": {
    "type": "TUMBLING",
    "durationMinutes": 60
  },
  "notificationConfig": {
    "channel": "EMAIL",
    "destination": "oncall@company.com"
  }
}
```

**BATCH — p99 latency degradation over last 15 minutes:**
```json
{
  "name": "P99 Latency Alert",
  "type": "BATCH",
  "enabled": true,
  "groupOperator": "OR",
  "conditionGroups": [{
    "operator": "AND",
    "conditions": [
      { "field": "AGGREGATE_P99_RESPONSE_TIME_MS", "operator": "GREATER_THAN", "value": "3000" },
      { "field": "AGGREGATE_REQUEST_COUNT",        "operator": "GREATER_THAN", "value": "50" }
    ]
  }],
  "windowConfig": {
    "type": "SLIDING",
    "durationMinutes": 15,
    "slideMinutes": 5
  },
  "notificationConfig": {
    "channel": "SLACK",
    "destination": "#sre-alerts"
  }
}
```

---

## Abstractions & Mock Implementations

Every infrastructure dependency is behind an interface.
Swap via Spring `@Profile` or `@ConditionalOnProperty` — zero logic changes.

```
Interface                  Mock (default profile)        Prod swap-in
─────────────────────────────────────────────────────────────────────────────
EventBus                   InMemoryEventBus              KafkaEventBus
                           LinkedBlockingQueue x2        KafkaTemplate +
                           (realtime + batch queues)     @KafkaListener groups

EventStore                 LocalFileEventStore           S3EventStore
                           Parquet on /tmp/...           S3Client.putObject()
                           same partition layout         same partition layout

AggregateComputer          DuckDBAggregateComputer       SparkAggregateComputer
                           DuckDB JDBC                   SparkSession on EMR/
                           reads local Parquet           Databricks + Iceberg

NotificationDispatcher     LoggingNotificationDispatcher SlackNotificationDispatcher
                           log.warn() + H2 audit table   EmailNotificationDispatcher
                                                         WebhookNotificationDispatcher

Catalog DB                 H2 in-memory                  PostgreSQL / Aurora
                           (same JPA entities/queries)   change JDBC URL + driver
```

### Swap Instructions

**EventBus → Kafka:**
Implement `EventBus` with `KafkaTemplate.send()`. Consumer side: `@KafkaListener`
replaces the `while(true) queue.take()` loop in `RealtimeWorker` and `BatchWorker`.
`RealtimeWorker` and `BatchScheduler` logic is completely unchanged.

**EventStore → S3:**
Implement `EventStore` using AWS SDK v2 `S3AsyncClient.putObject()`.
`TenantDataLocation.basePath` changes from `/tmp/...` to `s3://bucket/prefix`.
Parquet format and partition layout (`tenant_id=/year=/month=/day=`) are identical.

**AggregateComputer → Spark:**
Implement `AggregateComputer` using `SparkSession` pointed at an Iceberg catalog
(AWS Glue or Hive Metastore). The aggregate SQL is standard ANSI — minimal changes
between DuckDB and Spark SQL dialects. `TenantCatalogService` glob logic moves to
Iceberg table scan with partition pruning.

**Catalog DB → PostgreSQL:**
Change `spring.datasource.url` from `jdbc:h2:mem:...` to `jdbc:postgresql://...`.
JPA entities, repositories, and queries are unchanged. Add PostgreSQL driver dependency.

---

## Realtime Pipeline

```
RealtimeWorker (Runnable, N threads configured via pipeline.realtime.worker-threads)

loop:
  1. bytes  = realtimeQueue.take()                    blocks until event arrives
  2. event  = deserialize(bytes) → ApiEvent
  3. rules  = ruleCache.getOrLoad(event.tenantId)     in-memory cache, TTL 30s
              if miss → ruleRepository.findRealtimeRules(tenantId)
  4. fired  = realtimeRuleEvaluator.evaluate(event, rules)
  5. for each fired rule:
       notificationDispatcher.dispatch(rule, event)

  catch(Exception) → log error, continue loop         a bad event never kills the worker
```

**Rule evaluation per event:**
```
realtimeRuleEvaluator.evaluate(event, rules):
  for each rule where type=REALTIME and enabled=true:
    groupResults = groups.map(group → evaluateGroup(event, group))
    ruleResult   = applyOperator(rule.groupOperator, groupResults)
    if ruleResult: add to fired list

evaluateGroup(event, group):
  condResults = group.conditions.map(c →
    conditionEvaluator.evaluate(c, fieldExtractor.extract(event, c)))
  return applyOperator(group.operator, condResults)

applyOperator(AND, results) → results.allMatch(true)
applyOperator(OR,  results) → results.anyMatch(true)
```

**Rule cache:** `ConcurrentHashMap<tenantId, CachedEntry(rules, expiresAt)>`.
30s TTL — staleness acceptable for alerting. Invalidated eagerly on rule mutations.

---

## Batch Pipeline

```
BatchWriter (fed by BatchWorker thread reading from batchQueue):
  buffer = List<ApiEvent>                             accumulates in memory
  flush when: buffer.size() >= flushBatchSize         e.g. 100 events
           OR time since last flush >= flushIntervalMs e.g. 30s
  on flush:
    1. snapshot = drain buffer
    2. eventStore.write(tenantId, snapshot)           → Parquet file on disk
    3. catalog.recordWrite(tenantId, files, bytes)    → update H2 stats

BatchScheduler (@Scheduled, every 60s):
  tenants = ruleRepository.findTenantsWithBatchRules()
  for each tenant:
    rules = ruleRepository.findBatchRules(tenantId)
    for each rule:
      windowEnd   = Instant.now()
      windowStart = windowEnd - rule.windowConfig.durationMinutes
      glob        = catalog.getParquetGlob(tenantId, windowStart, windowEnd)
      metrics     = aggregateComputer.compute(glob, windowStart, windowEnd)
      fired       = aggregateRuleEvaluator.evaluate(metrics, rule)
      if fired: notificationDispatcher.dispatch(rule, metrics)
```

**Partition layout (Hive-style, understood by DuckDB + Iceberg + Spark):**
```
{basePath}/tenant_id={tenantId}/year=YYYY/month=MM/day=DD/events-{timestamp}.parquet
```

**Glob construction with partition pruning:**
```
same-day window  → {basePath}/tenant_id=abc/year=2026/month=02/day=26/**/*.parquet
multi-day window → {basePath}/tenant_id=abc/year=2026/**/*.parquet
```
Only relevant directory partitions are scanned — equivalent to Iceberg's metadata pruning.

**DuckDB aggregate query (constructed from catalog schema + window):**
```sql
SELECT
    COUNT(*)                                                        AS request_count,
    AVG(response_time_ms)                                           AS avg_response_time_ms,
    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY response_time_ms) AS p99_response_time_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY response_time_ms) AS p95_response_time_ms,
    SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END)
        * 100.0 / NULLIF(COUNT(*), 0)                              AS error_rate_percent,
    COUNT(DISTINCT request_path)                                    AS distinct_paths_count
FROM read_parquet(?)                  -- glob from TenantCatalogService
WHERE timestamp BETWEEN ? AND ?       -- windowStart, windowEnd from rule.windowConfig
```

Results mapped to `AggregateMetrics` → evaluated by `AggregateRuleEvaluator` using
the same AND/OR group logic as the realtime evaluator.

**Parquet schema (queryable fields only — bodies excluded):**
```
event_id, tenant_id, timestamp, received_at,
http_method, request_host, request_path, query_string,
status_code, status_class, response_time_ms,
environment, region, service_id, trace_id
```

---

## Rule Engine

### Shared Evaluator Contract

Both realtime and batch share the same `applyOperator` logic.
The only difference is the input: `ApiEvent` fields vs `AggregateMetrics` fields.

```java
// Extensible operator switch — same method used at condition and group level
private boolean applyOperator(ConditionGroupOperator op, List<Boolean> results) {
    return switch (op) {
        case AND -> results.stream().allMatch(b -> b);
        case OR  -> results.stream().anyMatch(b -> b);
        // V2: case NOT -> results.stream().noneMatch(b -> b);
        //     case XOR -> results.stream().filter(b -> b).count() == 1;
    };
}
```

### EventFieldExtractor

Extracts a condition's target field from an `ApiEvent` as a String.
Returns `null` if the field path is absent — never throws NPE.

Map-based fields (`REQUEST_HEADER`, `RESPONSE_HEADER`, `METADATA_TAG`) parse the
condition value as `"key=expectedValue"`, use the key for map lookup, and return
the map value. The `ConditionEvaluator` then compares against the value portion.

Aggregate fields (`AGGREGATE_*`) throw `IllegalArgumentException` if called from
the realtime path — a programming error, not a runtime condition.

### ConditionEvaluator

Compares extracted field value (String) against condition value using the operator.
Numeric operators (`GT`, `LT`, etc.) parse both sides as `double`.
`REGEX_MATCH` catches `PatternSyntaxException` — bad regex = no-match, not crash.

---

## Catalog (H2)

Lightweight metadata store — the same conceptual role as Iceberg's catalog in production.

**`tenant_data_location`** — one row per tenant
```
tenant_id        PK
base_path        /tmp/event-store/tenant-abc  (→ s3://bucket/prefix in prod)
file_format      PARQUET
partition_spec   year/month/day
last_written_at  updated on every BatchWriter flush
total_files      running count
total_size_bytes running total
```

**`tenant_schema_column`** — one row per queryable column per tenant
```
tenant_id, column_name, column_type (STRING|INT|BIGINT|TIMESTAMP), is_queryable
Standard columns registered on tenant onboarding.
Custom columns added when tenant defines new tag keys.
Schema evolution: old Parquet files return NULL for new columns — DuckDB handles gracefully.
```

**`TenantCatalogService` responsibilities:**
- `registerTenant(tenantId)` — creates location row + inserts all default schema columns
- `getParquetGlob(tenantId, windowStart, windowEnd)` — partition-pruned glob path
- `getQueryableSchema(tenantId)` — columns for Parquet writer + DuckDB SELECT
- `recordWrite(tenantId, fileCount, bytes)` — keeps catalog stats current
- `addCustomColumn(tenantId, name, type)` — schema evolution for custom tags

---

## Rule Management API

```
POST   /api/v1/tenants/{tenantId}/rules
  Body: Rule (without ruleId/timestamps — server assigns)
  Returns: 201 + created Rule

GET    /api/v1/tenants/{tenantId}/rules
  Params: ?type=REALTIME|BATCH   ?enabled=true|false
  Returns: 200 + List<Rule>

GET    /api/v1/tenants/{tenantId}/rules/{ruleId}
  Returns: 200 + Rule  |  404

PUT    /api/v1/tenants/{tenantId}/rules/{ruleId}
  Body: Rule (full replacement)
  Returns: 200 + updated Rule  |  404

DELETE /api/v1/tenants/{tenantId}/rules/{ruleId}
  Returns: 204  |  404

PATCH  /api/v1/tenants/{tenantId}/rules/{ruleId}/enable
PATCH  /api/v1/tenants/{tenantId}/rules/{ruleId}/disable
  Returns: 200 + updated Rule

POST   /api/v1/tenants/{tenantId}/rules/{ruleId}/test
  Body: ApiEvent (sample event)
  Returns: 200 + { "fired": true|false, "matchedGroups": [0, 2] }
  Dry-run only — no notifications dispatched
```

**Rule storage:** `conditionGroups`, `windowConfig`, `notificationConfig` stored as
JSON in CLOB columns. Avoids schema migrations as the rule model evolves.
`tenantId`, `type`, `enabled` are indexed real columns — all query patterns hit indexes.

---

## Ingestion API

```
POST /api/v1/events
  Headers: X-Tenant-Id: {tenantId}  (required)
  Body: ApiEventRequest (JSON)
  Returns: 202 Accepted + { "eventId": "uuid" }

POST /api/v1/events/batch
  Headers: X-Tenant-Id: {tenantId}
  Body: List<ApiEventRequest> (max 500 per call)
  Returns: 202 Accepted + { "accepted": N, "eventIds": [...] }

GET /api/v1/tenants/{tenantId}/stats
  Returns: { eventsReceived, queueDepth, lastWriteAt, totalFiles }

GET /actuator/health
GET /actuator/metrics
```

---

## Notification Service

```
NotificationDispatcher (interface)
  void dispatch(Rule rule, ApiEvent event)          — from realtime pipeline
  void dispatch(Rule rule, AggregateMetrics metrics) — from batch pipeline

LoggingNotificationDispatcher (mock)
  — formats message using rule.notificationConfig.messageTemplate
  — replaces {{status_code}}, {{path}}, {{response_time_ms}},
    {{tenant_id}}, {{rule_name}}, {{timestamp}} from event/metrics context
  — log.warn("NOTIFICATION FIRED ...")
  — persists NotificationRecord to H2 notification_log table

Production implementations (V2):
  SlackNotificationDispatcher  → POST Slack Incoming Webhook
  EmailNotificationDispatcher  → AWS SES SendEmail
  WebhookNotificationDispatcher → HTTP POST to tenant-configured URL
```

---

## Pipeline Simulation

`PipelineSimulator` runs at startup (disable via `pipeline.simulation.enabled=false`).
Demonstrates the complete pipeline end-to-end without manual curl commands.

```
On startup:
  1. Register "tenant-demo" in catalog
  2. Create REALTIME rule: status_code >= 500
  3. Create REALTIME rule: response_time_ms > 3000 AND environment = prod
  4. Create BATCH rule:    error_rate_percent >= 10, window 5min TUMBLING
  5. Emit 50 synthetic events over ~10 seconds:
       - 60% 2xx responses (200, 201)
       - 20% 4xx responses (400, 401, 404)
       - 20% 5xx responses (500, 502, 503)
       - response times: mix of fast (50ms) and slow (4000ms+)
       - realistic paths: /api/users, /api/payments, /api/orders
       - realistic headers, bodies, tags

Observe:
  Realtime alerts fire immediately in logs (grep "NOTIFICATION FIRED")
  After ~60s, batch scheduler runs DuckDB query over flushed Parquet files
  H2 console shows notification_log entries: http://localhost:8080/h2-console
```

---

## Test Plan

### Unit Tests

**`ConditionEvaluatorTest`**
```
EQUALS / NOT_EQUALS             case-insensitive string comparison
GREATER_THAN / LESS_THAN        numeric parsing (double), boundary values
GREATER_THAN_OR_EQUAL           exact threshold match (=) returns true
CONTAINS / NOT_CONTAINS         substring matching
STARTS_WITH / ENDS_WITH         prefix/suffix matching
REGEX_MATCH                     valid pattern matches, invalid regex = no-match (no crash)
null actualValue                always false, no NPE
non-numeric value with GT/LT    falls back to lexicographic comparison
```

**`EventFieldExtractorTest`**
```
REQUEST_METHOD/HOST/PATH/BODY   happy path extraction
REQUEST_HEADER                  "Content-Type=application/json" → headers.get("Content-Type")
RESPONSE_STATUS_CODE            int → String conversion
RESPONSE_STATUS_CLASS           "5xx" passthrough
METADATA_TAG                    "team=payments" → tags.get("team")
METADATA_ENVIRONMENT/REGION     happy path
null request/response fields    returns null, no NPE (null-safe chain)
AGGREGATE_* from realtime path  throws IllegalArgumentException
```

**`RealtimeRuleEvaluatorTest`**
```
Happy path                      single condition match → rule fires
No match                        condition not satisfied → rule does not fire
Disabled rule                   never fires regardless of event
BATCH type rule                 skipped by realtime evaluator (type filter)
AND group — all match           fires
AND group — partial match       does not fire
OR group — any match            fires
OR group — no match             does not fire
Multi-group OR rule             fires when any group matches
Multi-group AND rule            requires all groups to match, partial = no fire
Multiple rules                  returns only matching subset
Empty rule list                 returns empty list
Invalid regex                   no crash — treated as no-match
null body field                 no crash — condition evaluates to false
REQUEST_HEADER condition        correctly parses "key=value" format
METADATA_TAG condition          correctly parses "key=value" format
```

**`AggregateRuleEvaluatorTest`**
```
Fires when error rate > threshold
Does not fire when below threshold
AND conditions: error_rate AND request_count both must satisfy
OR conditions:  either metric triggers fire
REALTIME rule passed in         skipped (type filter)
Single-event fields in rule     throws IllegalArgumentException
Zero request_count              error_rate = 0, no division by zero
```

**`InMemoryEventBusTest`**
```
Publish routes to both realtime and batch queues
Realtime queue at capacity      offer() returns false, event dropped, batch unaffected
Batch queue at capacity         put() blocks producer thread
Concurrent publishers           thread-safe, no lost events
```

**`BatchWriterTest`**
```
Flushes when batch size reached (100 events)
Flushes when time interval elapses (clock injection for determinism)
Concurrent event writes         thread-safe accumulation
Empty buffer                    no-op flush, no file written
```

**`TenantCatalogServiceTest`**
```
registerTenant                  creates location row + default schema columns
getParquetGlob same-day         returns day-level partition path
getParquetGlob multi-day        returns year-level glob path
recordWrite                     increments file count and byte total correctly
addCustomColumn                 appears in subsequent getQueryableSchema result
duplicate column name           idempotent — no duplicate row inserted
```

### Integration Test — `PipelineIntegrationTest`

Full Spring Boot context, real H2, real DuckDB, real local filesystem.

```
Setup:
  Start full application context
  Register "test-tenant" via TenantCatalogService
  Create REALTIME rule: status_code >= 500
  Create BATCH rule: error_rate_percent >= 50, window 1min TUMBLING

Test 1 — Realtime fires on matching event:
  POST /api/v1/events  { status: 500, path: /api/test, env: prod }
  await(2s)
  → notification_log has 1 row: tenant=test-tenant, pipeline=REALTIME
  → rule fired in logs

Test 2 — Realtime does not fire on non-matching event:
  POST /api/v1/events  { status: 200, path: /api/test }
  await(1s)
  → no new rows in notification_log

Test 3 — Batch pipeline fires after flush:
  POST /api/v1/events x20 (15 with status 500, 5 with status 200)
  await(5s) → BatchWriter flush → Parquet file exists on disk
  call BatchScheduler.runBatchRules() directly
  → DuckDB computes error_rate = 75%
  → BATCH rule fires
  → notification_log has 1 new batch row

Test 4 — Rule CRUD lifecycle:
  POST   rule          → 201, ruleId assigned
  GET    rules         → list contains new rule
  PATCH  disable       → rule.enabled = false
  POST   event match   → no notification (rule disabled)
  PUT    rule update   → name changed, 200
  DELETE rule          → 204
  GET    deleted rule  → 404

Test 5 — Dry-run test endpoint:
  POST /rules/{id}/test  body=matching event    → { fired: true }
  POST /rules/{id}/test  body=non-match event   → { fired: false }
  → notification_log unchanged (dry-run produces no notifications)
```

---

## Running the Project

### Prerequisites

- Java 17+
- Maven 3.8+
- No Docker, no external services required

### Build & Run

```bash
git clone <repo>
cd api-event-pipeline
mvn clean package -DskipTests
java -jar target/api-event-pipeline-1.0.0-SNAPSHOT.jar
```

### Run Tests

```bash
mvn test                              # all tests
mvn test -Dtest=ConditionEvaluatorTest,RealtimeRuleEvaluatorTest    # unit only
mvn test -Dtest=PipelineIntegrationTest                             # integration only
```

### Observe the Pipeline

```
Application:    http://localhost:8080
H2 Console:     http://localhost:8080/h2-console
                JDBC URL: jdbc:h2:mem:pipeline  (no password)
Health:         http://localhost:8080/actuator/health
Metrics:        http://localhost:8080/actuator/metrics

Logs:
  grep "NOTIFICATION FIRED"   → see all fired notifications
  grep "BATCH RULE"           → see batch scheduler activity
  grep "RealtimeWorker"       → see per-event processing
```

### Quick Curl Test

```bash
# 1. Create a realtime rule
curl -s -X POST http://localhost:8080/api/v1/tenants/tenant-1/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "5xx Alert",
    "type": "REALTIME",
    "enabled": true,
    "groupOperator": "OR",
    "conditionGroups": [{
      "operator": "AND",
      "conditions": [
        {"field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500"}
      ]
    }],
    "notificationConfig": {"channel": "LOG", "destination": "console"}
  }' | jq .

# 2. Send a matching event
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-1" \
  -d '{
    "timestamp": "2026-02-26T12:00:00Z",
    "request": {
      "method": "POST",
      "host": "api.example.com",
      "path": "/api/payments",
      "headers": {"Content-Type": "application/json"},
      "body": "{\"amount\": 100}",
      "sizeBytes": 20
    },
    "response": {
      "statusCode": 500,
      "responseTimeMs": 234,
      "headers": {},
      "body": "{\"error\": \"internal server error\"}",
      "sizeBytes": 40
    },
    "metadata": {
      "serviceId": "payment-svc",
      "environment": "prod",
      "region": "us-east-1",
      "tags": {"team": "payments"}
    }
  }' | jq .

# 3. Check notification log
curl -s http://localhost:8080/api/v1/tenants/tenant-1/notifications | jq .

# 4. Dry-run rule test
curl -s -X POST http://localhost:8080/api/v1/tenants/tenant-1/rules/{ruleId}/test \
  -H "Content-Type: application/json" \
  -d '{ same event body as above }' | jq .
```

---

## Configuration Reference

```yaml
pipeline:
  queue:
    realtime-capacity: 10000      # offer() — latency over durability
    batch-capacity: 50000         # put()   — durability over latency

  event-store:
    base-path: /tmp/api-event-pipeline/events
    format: PARQUET

  batch:
    flush-interval-ms: 30000      # flush every 30s
    flush-batch-size: 100         # or every 100 events
    scheduler-interval-ms: 60000  # batch rule evaluation frequency

  duckdb:
    threads: 4                    # query parallelism

  realtime:
    worker-threads: 2             # concurrent event processors

  rule-cache-ttl-seconds: 30      # per-tenant rule cache TTL

  simulation:
    enabled: true                 # generate synthetic events on startup
    event-count: 50
    interval-ms: 200
```

---

## Assumptions & Trade-offs

**Single JAR / monolith:** All components in one process. In production: separate services
for ingestion, realtime processing, and batch processing with independent scaling.
Package structure mirrors microservice boundaries for easy future extraction.

**AND/OR only:** `ConditionGroupOperator` supports AND and OR. Using an enum (not boolean)
means adding NOT, XOR, or NAND in V2 is one new enum value + one switch case.
Two-level group model (conditions within groups, groups within rule) covers 95% of real
alerting needs without requiring a full expression tree.

**LinkedBlockingQueue instead of Kafka:** Same fan-out semantics, zero infrastructure.
`offer()` for realtime (latency over durability), `put()` for batch (durability over
latency) — mirrors Kafka producer `acks` configuration. Not durable across restarts.

**Local filesystem instead of S3:** Same Parquet files, same Hive-style partition layout.
`EventStore` interface is the exact swap point. `TenantDataLocation.basePath` changes
from `/tmp/...` to `s3://...`.

**DuckDB instead of Spark:** Reads same Parquet files Spark would. `AggregateComputer`
interface is the swap point. DuckDB aggregate SQL is standard ANSI — minimal dialect
changes to run on Spark. Single machine only; no horizontal scale.

**H2 instead of PostgreSQL:** Identical JPA entities and queries. Change JDBC URL and
driver — nothing else changes.

**Structured batch conditions instead of user SQL:** Pre-computed AGGREGATE_* fields
cover common use cases safely. Raw SQL would require query validation, tenant isolation
enforcement, and resource quotas. Documented as a V2 feature.

**Rule storage as JSON CLOB:** Condition groups serialized as JSON. Trade-off: not SQL-
queryable inside conditions (acceptable — we never filter by condition content). Avoids
schema migrations as rule model evolves. `tenantId`/`type`/`enabled` are real indexed
columns for all actual query patterns.

**Full body storage (100kb):** In production: strip sensitive headers pre-ingestion,
run DLP scanning, encrypt at rest with per-tenant KMS keys, truncate with `truncated=true`.

**Rule cache 30s TTL:** Up to 30s staleness on rule changes. Acceptable for alerting.
Cache invalidated eagerly on mutations in `RuleService`.

---

## V2 Improvements

**Operators**
- [ ] Add `NOT` to `ConditionGroupOperator` — one enum value + one switch case
- [ ] Add `XOR` to `ConditionGroupOperator`
- [ ] Add `NOT_REGEX_MATCH` to `RuleOperator`

**Infrastructure**
- [ ] `KafkaEventBus` — replace `InMemoryEventBus`
- [ ] `S3EventStore` — replace `LocalFileEventStore`
- [ ] `SparkAggregateComputer` — replace `DuckDBAggregateComputer` with Iceberg catalog
- [ ] PostgreSQL — replace H2
- [ ] Real notification dispatchers (Slack, Email/SES, Webhook)

**Rule Engine**
- [ ] Custom SQL batch rules — `conditionType: CUSTOM_SQL` with mandatory tenant_id injection
- [ ] Rule versioning — history of changes per rule
- [ ] Rule templates — library of common patterns tenants can clone
- [ ] Historical dry-run — test rule against past events, not just a single sample

**Pipeline**
- [ ] Protobuf serialization on event bus (replace JSON bytes)
- [ ] Dead letter queue — events that fail processing after N retries
- [ ] Deduplication by `eventId` — idempotent re-delivery on Kafka retry
- [ ] Backpressure metrics via Micrometer — queue depth, drop rate, processing lag

**Security**
- [ ] Per-tenant API key authentication on ingestion endpoint
- [ ] Rate limiting per tenant
- [ ] Per-tenant KMS encryption keys for Parquet files at rest
- [ ] Rule change audit log (who, what, when)

**Observability**
- [ ] Structured JSON logging with `traceId` threaded through all components
- [ ] Micrometer counters: events/sec, rules evaluated/sec, notifications fired/min
- [ ] Queue depth gauges exposed to Prometheus
