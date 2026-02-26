package com.harness.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiEventPipelineApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApiEventPipelineApplication.class, args);
  }
}

