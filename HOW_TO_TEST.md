# How to Test

## Prerequisites

- Java 17+
- Maven

```bash
mvn clean package -DskipTests
```

Open `http://localhost:8080` in a browser to see the **live notification dashboard** alongside console logs.

![Dashboard](docs/dashboard.png)

---

## 1. Realtime Pipeline

Tests that ingesting an event matching a REALTIME rule produces a notification.

### Start the app

```bash
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

### Create a realtime rule

```bash
curl -s -X POST "http://localhost:8080/api/v1/tenants/tenant-1/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Any 5xx",
    "type": "REALTIME",
    "enabled": true,
    "groupOperator": "AND",
    "conditionGroups": [{
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500" }
      ]
    }],
    "notificationConfig": { "channel": "LOG", "destination": "console" }
  }' | python3 -m json.tool
```

### Send a matching event

```bash
curl -s -X POST "http://localhost:8080/api/v1/events" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-1" \
  -d '{
    "timestamp": "2026-02-26T12:00:00Z",
    "request": { "method": "GET", "host": "api.example.com", "path": "/api/payments" },
    "response": { "statusCode": 500, "responseTimeMs": 200 },
    "metadata": { "serviceId": "svc-1", "environment": "prod", "region": "us-east-1" }
  }'
```

### Verify

**Console:** you should see a log line like:

```
NOTIFICATION FIRED: tenantId=tenant-1, ruleName=Any 5xx, ruleType=REALTIME, statusCode=500, path=/api/payments, env=prod
```

**Dashboard:** a red REALTIME card appears instantly at `http://localhost:8080`.

Sending an event with `statusCode: 200` should produce no notification in either place.

---

## 2. Batch Storage

Tests that ingested events are flushed to JSONL files on disk. No rules needed -- all events go to the batch queue automatically.

### Start the app with a short flush interval

```bash
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar \
  --pipeline.batch.flush-interval-ms=5000
```

### Send a few events

```bash
for i in 1 2 3 4 5; do
  curl -s -X POST "http://localhost:8080/api/v1/events" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: tenant-1" \
    -d '{
      "timestamp": "2026-02-26T12:00:00Z",
      "request": { "method": "GET", "host": "api.example.com", "path": "/api/payments" },
      "response": { "statusCode": 200, "responseTimeMs": 150 },
      "metadata": { "serviceId": "svc-1", "environment": "prod", "region": "us-east-1" }
    }' > /dev/null
done
```

### Verify

Wait ~10 seconds, then check:

```bash
find /tmp/api-event-pipeline/batch-events -name '*.jsonl'
head -n 3 /tmp/api-event-pipeline/batch-events/tenant_id=tenant-1/year=2026/month=02/day=26/events-*.jsonl
```

Each line is a flattened JSON object with fields like `event_id`, `status_code`, `response_time_ms`, etc.

---

## 3. Batch Aggregation (DuckDB)

Tests that a BATCH rule evaluates a `COUNT(*)` query over stored JSONL files and fires a notification when the count exceeds the threshold.

### Start the app with short intervals

```bash
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar \
  --pipeline.batch.flush-interval-ms=5000 \
  --pipeline.batch.aggregation-interval-ms=15000
```

This flushes events to disk every 5s and runs batch rule aggregation every 15s.

### Create a BATCH rule

```bash
curl -s -X POST "http://localhost:8080/api/v1/tenants/tenant-1/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High 5xx rate",
    "type": "BATCH",
    "enabled": true,
    "groupOperator": "AND",
    "conditionGroups": [{
      "operator": "AND",
      "conditions": [
        { "field": "RESPONSE_STATUS_CODE", "operator": "GREATER_THAN_OR_EQUAL", "value": "500" }
      ]
    }],
    "notificationConfig": { "channel": "LOG", "destination": "console" },
    "windowMinutes": 60,
    "countThreshold": 2
  }' | python3 -m json.tool
```

This rule says: "if there are >= 2 events with status code >= 500 in the last 60 minutes, fire a notification."

### Send matching events

```bash
for i in 1 2 3; do
  curl -s -X POST "http://localhost:8080/api/v1/events" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: tenant-1" \
    -d '{
      "timestamp": "2026-02-26T12:40:00Z",
      "request": { "method": "POST", "host": "api.example.com", "path": "/api/orders" },
      "response": { "statusCode": 503, "responseTimeMs": 250 },
      "metadata": { "serviceId": "order-svc", "environment": "prod", "region": "us-east-1" }
    }'
  echo " -> event $i sent"
done
```

### Verify

Wait ~20 seconds (5s for flush + 15s for aggregation).

**Console:** you should see:

```
Batch rule 'High 5xx rate': count=3, threshold=2
BATCH THRESHOLD BREACHED: tenantId=tenant-1, ruleName=High 5xx rate, threshold=2, actualCount=3, windowMinutes=60
```

**Dashboard:** an orange BATCH card appears at `http://localhost:8080` showing the threshold breach details.

---

## Unit Tests

```bash
mvn clean test
```

Requires Java 17 (Mockito/Byte Buddy does not support JDK 25+).
