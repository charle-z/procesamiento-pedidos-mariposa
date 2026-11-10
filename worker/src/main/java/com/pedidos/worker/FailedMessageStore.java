package com.pedidos.worker;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class FailedMessageStore {
  private final StringRedisTemplate redis;

  public FailedMessageStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void save(ConsumerRecord<String, String> record, String reason, int attempts) {
    String key =
        "order:failed:" + record.topic() + ":" + record.partition() + ":" + record.offset();
    redis
        .opsForHash()
        .putAll(
            key,
            Map.of(
                "message",
                record.value() == null ? "null" : record.value(),
                "reason",
                reason,
                "attempts",
                Integer.toString(attempts)));
  }
}
