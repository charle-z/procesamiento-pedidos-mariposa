package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class CatalogClientTest {
  private DisposableServer server;

  @AfterEach
  void stop() {
    if (server != null) server.disposeNow();
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

  private CatalogClient client(int retries) {
    var properties =
        new OrderProperties(
            "pedidos",
            "http://127.0.0.1:" + server.port(),
            retries,
            Duration.ofMillis(10),
            Duration.ofSeconds(2),
            Duration.ofMinutes(1));
    return new CatalogClient(WebClient.builder(), properties);
  }
}
