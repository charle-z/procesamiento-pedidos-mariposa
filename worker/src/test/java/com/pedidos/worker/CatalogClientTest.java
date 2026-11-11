package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

class CatalogClientTest {
  private DisposableServer server;
  private final ConnectionProvider connections = ConnectionProvider.create("test-catalog", 4);

  @AfterEach
  void stop() {
    if (server != null) server.disposeNow();
    connections.dispose();
  }

  @Test
  void retriesTemporaryErrors() {
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(
                routes ->
                    routes.get(
                        "/products/{id}",
                        (request, response) -> {
                          if (requests.incrementAndGet() < 3) return response.status(503).send();
                          return response
                              .header("Content-Type", "application/json")
                              .sendString(
                                  Mono.just("{\"id\":\"p1\",\"name\":\"Laptop\",\"price\":999}"));
                        }))
            .bindNow();
    CatalogClient client = client(3);
    assertEquals("Laptop", client.product("p1").name());
    assertEquals(3, requests.get());
  }

  @Test
  void doesNotRetryMissingProduct() {
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(
                routes ->
                    routes.get(
                        "/products/{id}",
                        (request, response) -> {
                          requests.incrementAndGet();
                          return response.status(404).send();
                        }))
            .bindNow();
    assertThrows(InvalidOrderException.class, () -> client(3).product("missing"));
    assertEquals(1, requests.get());
  }

  @Test
  void fetchesMissingProductsInOneBatchAndKeepsOrderAndCache() {
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(
                routes ->
                    routes.post(
                        "/products/batch",
                        (request, response) -> {
                          requests.incrementAndGet();
                          return response
                              .header("Content-Type", "application/json")
                              .sendString(
                                  Mono.just(
                                      "[{\"id\":\"p1\",\"name\":\"Laptop\",\"price\":999},"
                                          + "{\"id\":\"p2\",\"name\":\"Monitor\",\"price\":250}]"));
                        }))
            .bindNow();
    CatalogClient client = client(3);
    var products = client.products(List.of("p2", "p1", "p2"));
    assertEquals(
        List.of("p2", "p1", "p2"), products.stream().map(CatalogClient.Product::id).toList());
    client.products(List.of("p1", "p2"));
    assertEquals(1, requests.get());
  }

  @Test
  void retriesRateLimitAndReportsNumberOfAttempts() {
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(
                routes ->
                    routes.post(
                        "/products/batch",
                        (request, response) -> {
                          requests.incrementAndGet();
                          return response.status(429).send();
                        }))
            .bindNow();
    CatalogUnavailableException error =
        assertThrows(CatalogUnavailableException.class, () -> client(2).products(List.of("p1")));
    assertEquals(3, error.attempts());
    assertEquals(3, requests.get());
  }

  private CatalogClient client(int retries) {
    var properties =
        new OrderProperties(
            "pedidos",
            "http://127.0.0.1:" + server.port(),
            retries,
            Duration.ofMillis(10),
            Duration.ofSeconds(2),
            Duration.ofMinutes(1));
    return new CatalogClient(
        WebClient.builder(), properties, new ConcurrentMapCacheManager("products"), connections);
  }
}
