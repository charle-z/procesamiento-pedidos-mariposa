package com.pedidos.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pedidos")
public record OrderProperties(
    String topic,
    String catalogUrl,
    int maxRetries,
    Duration retryDelay,
    Duration requestTimeout,
    Duration lockTtl) {}
