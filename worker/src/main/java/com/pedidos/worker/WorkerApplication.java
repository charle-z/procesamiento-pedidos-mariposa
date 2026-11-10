package com.pedidos.worker;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }

  @Bean
  DefaultErrorHandler infrastructureErrorHandler() {
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setClassifications(Map.of(), true);
    return handler;
  }
}
