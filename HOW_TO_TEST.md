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

