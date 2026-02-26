## How to Test Realtime Alerts

### 1. Start the app

From the project root:

```bash
mvn clean package
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

### 2. Create a simple realtime rule

```bash
curl -s -X POST "http://localhost:8080/api/v1/tenants/tenant-1/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "id": null,
    "tenantId": null,
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
    "notificationConfig": { "channel": "LOG", "destination": "console" },
    "createdAt": null,
    "updatedAt": null
  }'
```

### 3. Send an event that should trigger the rule

```bash
curl -s -X POST "http://localhost:8080/api/v1/events" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-1" \
  -d '{
    "timestamp": "2026-02-26T12:00:00Z",
    "request": {
      "method": "GET",
      "host": "api.example.com",
      "path": "/api/payments",
      "queryString": "page=1",
      "headers": {"Content-Type": "application/json"},
      "body": "{\"amount\":100}",
      "sizeBytes": 20
    },
    "response": {
      "statusCode": 500,
      "responseTimeMs": 200,
      "headers": {},
      "body": "{\"error\":\"internal\"}",
      "sizeBytes": 10
    },
    "metadata": {
      "serviceId": "svc-1",
      "serviceName": "payment-svc",
      "environment": "prod",
      "region": "us-east-1",
      "hostIp": "127.0.0.1",
      "traceId": "trace-1",
      "tags": {"team": "payments"}
    }
  }'
```

**Verify**: in the app logs you should see a line like:

```text
NOTIFICATION FIRED: tenantId=tenant-1, ruleName=Any 5xx, ...
```

If you send the same request with `statusCode: 200`, no such line should appear.

---

## How to Test Batch Storage

### 1. Start the app with a short batch interval

```bash
export SPRING_APPLICATION_JSON='{"pipeline":{"batch":{"flush-interval-ms":10000}}}'
mvn clean package
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

### 2. Send a few events

```bash
for i in {1..5}; do
  curl -s -X POST "http://localhost:8080/api/v1/events" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: tenant-1" \
    -d '{
      "timestamp": "2026-02-26T12:00:00Z",
      "request": {
        "method": "GET",
        "host": "api.example.com",
        "path": "/api/payments",
        "queryString": "page=1",
        "headers": {"Content-Type": "application/json"},
        "body": "{\"amount\":100}",
        "sizeBytes": 20
      },
      "response": {
        "statusCode": 200,
        "responseTimeMs": 150,
        "headers": {},
        "body": "{}",
        "sizeBytes": 2
      },
      "metadata": {
        "serviceId": "svc-1",
        "serviceName": "payment-svc",
        "environment": "prod",
        "region": "us-east-1",
        "hostIp": "127.0.0.1",
        "traceId": "trace-1",
        "tags": {"team": "payments"}
      }
    }' >/dev/null
done
```

### 3. Verify batch files on disk

Wait 20–30 seconds, then in another terminal:

```bash
BASE=/tmp/api-event-pipeline/batch-events
find "$BASE" -type f -name '*.jsonl'
```

You should see something like:

```text
/tmp/api-event-pipeline/batch-events/tenant_id=tenant-1/year=2026/month=02/day=26/events-<timestamp>-<uuid>.jsonl
```

Inspect a few lines:

```bash
head -n 5 /tmp/api-event-pipeline/batch-events/tenant_id=tenant-1/year=2026/month=02/day=26/events-*.jsonl
```

You should see one JSON object per line with flattened event fields (tenant, timestamps, path, status, latency, environment, etc.). This confirms that the batch pipeline is draining the queue and persisting time-series data.

## How to Test the Realtime Pipeline

### 1. Build and run the application

From the project root:

```bash
mvn clean package
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

The app starts on `http://localhost:8080`.

### 2. Create a realtime rule

In a new terminal, create a simple "any 5xx" realtime rule for `tenant-1`:

```bash
curl -s -X POST "http://localhost:8080/api/v1/tenants/tenant-1/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "id": null,
    "tenantId": null,
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
    "notificationConfig": { "channel": "LOG", "destination": "console" },
    "createdAt": null,
    "updatedAt": null
  }'
```

You should get back the created rule as JSON.

### 3. Ingest an event that should fire the rule

Send an event for `tenant-1` with a 5xx status:

```bash
curl -s -X POST "http://localhost:8080/api/v1/events" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-1" \
  -d '{
    "timestamp": "2026-02-26T12:00:00Z",
    "request": {
      "method": "GET",
      "host": "api.example.com",
      "path": "/api/payments",
      "queryString": "page=1",
      "headers": {"Content-Type": "application/json"},
      "body": "{\"amount\":100}",
      "sizeBytes": 20
    },
    "response": {
      "statusCode": 500,
      "responseTimeMs": 200,
      "headers": {},
      "body": "{\"error\":\"internal\"}",
      "sizeBytes": 10
    },
    "metadata": {
      "serviceId": "svc-1",
      "serviceName": "payment-svc",
      "environment": "prod",
      "region": "us-east-1",
      "hostIp": "127.0.0.1",
      "traceId": "trace-1",
      "tags": {"team": "payments"}
    }
  }'
```

Response should be `202 Accepted` with a JSON body containing an `eventId`.

### 4. Verify notifications in logs

In the terminal where the JAR is running, you should see log lines similar to:

```text
NOTIFICATION FIRED: tenantId=tenant-1, ruleName=Any 5xx, ruleType=REALTIME, statusCode=500, path=/api/payments, env=prod
```

If you send a non-matching event (for example, `statusCode: 200`), no `NOTIFICATION FIRED` line should appear, confirming the rule evaluation is working correctly.

---

## How to Test the Batch Pipeline

### 1. Build and run the application

Same as for the realtime pipeline:

```bash
mvn clean package
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

By default, the batch scheduler flushes every 10 minutes (`pipeline.batch.flush-interval-ms`).

To speed up manual testing, you can override this for a single run:

```bash
export SPRING_APPLICATION_JSON='{"pipeline":{"batch":{"flush-interval-ms":10000}}}'
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

### 2. Ingest some events

Send a few events for a tenant (they automatically go to the batch queue via the `EventBus`):

```bash
for i in {1..5}; do
  curl -s -X POST "http://localhost:8080/api/v1/events" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: tenant-1" \
    -d '{
      "timestamp": "2026-02-26T12:00:00Z",
      "request": {
        "method": "GET",
        "host": "api.example.com",
        "path": "/api/payments",
        "queryString": "page=1",
        "headers": {"Content-Type": "application/json"},
        "body": "{\"amount\":100}",
        "sizeBytes": 20
      },
      "response": {
        "statusCode": 200,
        "responseTimeMs": 150,
        "headers": {},
        "body": "{}",
        "sizeBytes": 2
      },
      "metadata": {
        "serviceId": "svc-1",
        "serviceName": "payment-svc",
        "environment": "prod",
        "region": "us-east-1",
        "hostIp": "127.0.0.1",
        "traceId": "trace-1",
        "tags": {"team": "payments"}
      }
    }' >/dev/null
done
```

### 3. Wait for the batch flush

Wait a bit longer than the configured interval (for example, 20–30 seconds if using a 10s interval).  
In the application logs you should see a message similar to:

```text
BatchWriter flushed 5 events into 1 Parquet file(s)
```

### 4. Inspect batch output files

Batch files are written under a tenant and date partitioned directory structure:

```bash
BASE=/tmp/api-event-pipeline/batch-events
find "$BASE" -type f -name '*.jsonl'
```

You should see paths similar to:

```text
/tmp/api-event-pipeline/batch-events/tenant_id=tenant-1/year=2026/month=02/day=26/events-<timestamp>-<uuid>.jsonl
```

Inspect a few rows:

```bash
head -n 5 /tmp/api-event-pipeline/batch-events/tenant_id=tenant-1/year=2026/month=02/day=26/events-*.jsonl
```

Each line is a flattened JSON object representing a single API event, with fields such as `event_id`, `tenant_id`, `timestamp`, `status_code`, `response_time_ms`, and environment/region/service metadata. This confirms that the batch pipeline is draining the queue and persisting time-series data correctly.

## How to Test the Realtime Pipeline

### 1. Build and run the application

From the project root:

```bash
mvn clean package
java -jar target/api-event-pipeline-0.0.1-SNAPSHOT.jar
```

The app starts on `http://localhost:8080`.

### 2. Create a realtime rule

In a new terminal, create a simple "any 5xx" realtime rule for `tenant-1`:

```bash
curl -s -X POST "http://localhost:8080/api/v1/tenants/tenant-1/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "id": null,
    "tenantId": null,
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
    "notificationConfig": { "channel": "LOG", "destination": "console" },
    "createdAt": null,
    "updatedAt": null
  }'
```

You should get back the created rule as JSON.

### 3. Ingest an event that should fire the rule

Send an event for `tenant-1` with a 5xx status:

```bash
curl -s -X POST "http://localhost:8080/api/v1/events" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-1" \
  -d '{
    "timestamp": "2026-02-26T12:00:00Z",
    "request": {
      "method": "GET",
      "host": "api.example.com",
      "path": "/api/payments",
      "queryString": "page=1",
      "headers": {"Content-Type": "application/json"},
      "body": "{\"amount\":100}",
      "sizeBytes": 20
    },
    "response": {
      "statusCode": 500,
      "responseTimeMs": 200,
      "headers": {},
      "body": "{\"error\":\"internal\"}",
      "sizeBytes": 10
    },
    "metadata": {
      "serviceId": "svc-1",
      "serviceName": "payment-svc",
      "environment": "prod",
      "region": "us-east-1",
      "hostIp": "127.0.0.1",
      "traceId": "trace-1",
      "tags": {"team": "payments"}
    }
  }'
```

Response should be `202 Accepted` with a JSON body containing an `eventId`.

### 4. Verify notifications in logs

In the terminal where the JAR is running, you should see log lines similar to:

```text
NOTIFICATION FIRED: tenantId=tenant-1, ruleName=Any 5xx, ruleType=REALTIME, statusCode=500, path=/api/payments, env=prod
```

If you send a non-matching event (for example, `statusCode: 200`), no `NOTIFICATION FIRED` line should appear, confirming the rule evaluation is working correctly.

