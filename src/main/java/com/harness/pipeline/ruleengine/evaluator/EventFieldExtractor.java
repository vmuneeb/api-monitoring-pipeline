package com.harness.pipeline.ruleengine.evaluator;

import com.harness.pipeline.enums.RuleConditionField;
import com.harness.pipeline.model.ApiEvent;
import com.harness.pipeline.model.ConditionDto;
import java.util.Map;

public class EventFieldExtractor {

  public String extract(ApiEvent event, ConditionDto condition) {
    return extract(event, condition.field(), condition.value());
  }

  public String extract(ApiEvent event, RuleConditionField field, String conditionValue) {
    if (event == null) {
      return null;
    }

    ApiEvent.HttpRequest request = event.request();
    ApiEvent.HttpResponse response = event.response();
    ApiEvent.ServiceMetadata metadata = event.metadata();

    return switch (field) {
      case REQUEST_METHOD -> request != null ? request.method() : null;
      case REQUEST_HOST -> request != null ? request.host() : null;
      case REQUEST_PATH -> request != null ? request.path() : null;
      case REQUEST_QUERY_STRING -> request != null ? request.queryString() : null;
      case REQUEST_HEADER -> {
        String key = extractKey(conditionValue);
        yield request != null ? getFromMap(request.headers(), key) : null;
      }
      case REQUEST_BODY -> request != null ? request.body() : null;

      case RESPONSE_STATUS_CODE -> response != null && response.statusCode() != null
          ? Integer.toString(response.statusCode())
          : null;
      case RESPONSE_STATUS_CLASS -> response != null ? response.statusClass() : null;
      case RESPONSE_TIME_MS -> response != null && response.responseTimeMs() != null
          ? Long.toString(response.responseTimeMs())
          : null;
      case RESPONSE_HEADER -> {
        String key = extractKey(conditionValue);
        yield response != null ? getFromMap(response.headers(), key) : null;
      }

      case METADATA_ENVIRONMENT -> metadata != null ? metadata.environment() : null;
      case METADATA_REGION -> metadata != null ? metadata.region() : null;
      case METADATA_TAG -> {
        String key = extractKey(conditionValue);
        yield metadata != null ? getFromMap(metadata.tags(), key) : null;
      }

      case AGGREGATE_ERROR_RATE_PERCENT,
           AGGREGATE_P99_RESPONSE_TIME_MS,
           AGGREGATE_REQUEST_COUNT,
           AGGREGATE_DISTINCT_PATHS_COUNT ->
          throw new IllegalArgumentException(
              "Aggregate fields are not supported in realtime event extraction: " + field);
    };
  }

  private String extractKey(String conditionValue) {
    if (conditionValue == null) {
      return null;
    }
    int idx = conditionValue.indexOf('=');
    return idx > 0 ? conditionValue.substring(0, idx) : conditionValue;
  }

  private String getFromMap(Map<String, String> map, String key) {
    if (map == null || key == null) {
      return null;
    }
    return map.get(key);
  }
}

