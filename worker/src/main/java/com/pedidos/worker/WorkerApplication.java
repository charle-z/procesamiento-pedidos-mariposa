package com.pedidos.worker;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import reactor.netty.resources.ConnectionProvider;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
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

  @Bean(destroyMethod = "dispose")
  ConnectionProvider catalogConnections() {
    return ConnectionProvider.builder("catalogo")
        .maxConnections(16)
        .pendingAcquireMaxCount(32)
        .pendingAcquireTimeout(Duration.ofSeconds(2))
        .maxIdleTime(Duration.ofSeconds(30))
        .build();
  }
}
