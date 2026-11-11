package com.pedidos.worker;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class OrderLock {
  private static final DefaultRedisScript<Long> RELEASE =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else"
              + " return 0 end",
          Long.class);
  private static final DefaultRedisScript<Long> RENEW =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1],"
              + " ARGV[2]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redis;
  private final OrderProperties properties;
  private final ScheduledExecutorService renewals =
      Executors.newScheduledThreadPool(
          2,
          task -> {
            Thread thread = new Thread(task, "order-lock-renewal");
            thread.setDaemon(true);
            return thread;
          });

  public OrderLock(StringRedisTemplate redis, OrderProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  public String acquire(String orderId) {
    String token = UUID.randomUUID().toString();
    Boolean acquired = redis.opsForValue().setIfAbsent(key(orderId), token, properties.lockTtl());
    return Boolean.TRUE.equals(acquired) ? token : null;
  }

  public void release(String orderId, String token) {
    redis.execute(RELEASE, List.of(key(orderId)), token);
  }

  public Lease maintain(String orderId, String token) {
    return new Lease(orderId, token);
  }

  @PreDestroy
  void shutdown() {
    renewals.shutdownNow();
  }

  public final class Lease implements AutoCloseable {
    private final String orderId;
    private final String token;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final ScheduledFuture<?> renewal;

    private Lease(String orderId, String token) {
      this.orderId = orderId;
      this.token = token;
      long interval = Math.max(1, properties.lockTtl().toMillis() / 3);
      renewal =
          renewals.scheduleWithFixedDelay(
              this::renewInBackground, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void renewInBackground() {
      try {
        requireOwnership();
      } catch (RuntimeException failure) {
        lost.set(true);
      }
    }

    public void requireOwnership() {
      if (lost.get()) throw new IllegalStateException("Bloqueo perdido: " + orderId);
      try {
        Long renewed =
            redis.execute(
                RENEW,
                List.of(key(orderId)),
                token,
                Long.toString(properties.lockTtl().toMillis()));
        if (!Long.valueOf(1).equals(renewed)) {
          lost.set(true);
          throw new IllegalStateException("Bloqueo perdido: " + orderId);
        }
      } catch (RuntimeException failure) {
        lost.set(true);
        throw failure;
      }
      if (lost.get()) throw new IllegalStateException("Bloqueo perdido: " + orderId);
    }

    @Override
    public void close() {
      renewal.cancel(false);
    }
  }

  private String key(String orderId) {
    return "order:lock:" + orderId;
  }
}
