package com.harness.pipeline.repository;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class RuleJsonConverter implements AttributeConverter<String, String> {

  @Override
  public String convertToDatabaseColumn(String attribute) {
    return attribute;
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    return dbData;
  }
}

