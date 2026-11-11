package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "true")
class OrderLockIntegrationTest {
  private LettuceConnectionFactory connection;
  private OrderLock lock;
  private final String orderId = "test-" + UUID.randomUUID();

  @BeforeEach
  void connect() {
    String host = System.getenv().getOrDefault("REDIS_TEST_HOST", "127.0.0.1");
    int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6381"));
    connection = new LettuceConnectionFactory(host, port);
    connection.afterPropertiesSet();
    var redis = new StringRedisTemplate(connection);
    redis.afterPropertiesSet();
    var properties =
        new OrderProperties(
            "test",
            "http://localhost",
            1,
            Duration.ofMillis(10),
            Duration.ofSeconds(1),
            Duration.ofSeconds(3));
    lock = new OrderLock(redis, properties);
  }

  @AfterEach
  void close() {
    lock.shutdown();
    connection.destroy();
  }

  @Test
  void onlyOneConcurrentOwnerCanAcquire() throws Exception {
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    List<java.util.concurrent.Future<String>> attempts = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++) {
        attempts.add(
            executor.submit(
                () -> {
                  start.await();
                  return lock.acquire(orderId);
                }));
      }
      start.countDown();
      List<String> tokens = new ArrayList<>();
      for (var attempt : attempts) {
        String token = attempt.get(5, TimeUnit.SECONDS);
        if (token != null) tokens.add(token);
      }
      assertEquals(1, tokens.size());
      lock.release(orderId, tokens.getFirst());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredOwnerCannotReleaseNewOwnersLock() throws Exception {
    String oldToken = lock.acquire(orderId);
    assertNotNull(oldToken);
    Thread.sleep(3300);
    String newToken = lock.acquire(orderId);
    assertNotNull(newToken);
    lock.release(orderId, oldToken);
    assertNull(lock.acquire(orderId));
    lock.release(orderId, newToken);
  }

  @Test
  void activeLeaseKeepsLockPastOriginalTtl() throws Exception {
    String token = lock.acquire(orderId);
    assertNotNull(token);
    try (var lease = lock.maintain(orderId, token)) {
      Thread.sleep(4200);
      lease.requireOwnership();
      assertNull(lock.acquire(orderId));
    }
    lock.release(orderId, token);
    assertNotNull(lock.acquire(orderId));
  }

  @Test
  void leaseDetectsLostOwnershipAndDoesNotReleaseSuccessor() {
    String oldToken = lock.acquire(orderId);
    assertNotNull(oldToken);
    String newToken;
    try (var lease = lock.maintain(orderId, oldToken)) {
      lock.release(orderId, oldToken);
      newToken = lock.acquire(orderId);
      assertNotNull(newToken);
      assertThrows(IllegalStateException.class, lease::requireOwnership);
    }
    lock.release(orderId, oldToken);
    assertNull(lock.acquire(orderId));
    lock.release(orderId, newToken);
  }
}
