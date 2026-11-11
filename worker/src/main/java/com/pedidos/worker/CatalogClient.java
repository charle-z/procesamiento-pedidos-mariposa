package com.pedidos.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

@Component
public class CatalogClient {
  public record Customer(String id, String name, boolean active) {}

  public record Product(String id, String name, String description, long price) {}

  private final WebClient client;
  private final OrderProperties properties;
  private final CacheManager caches;

  public CatalogClient(
      WebClient.Builder builder,
      OrderProperties properties,
      CacheManager caches,
      ConnectionProvider connections) {
    this.client =
        builder
            .baseUrl(properties.catalogUrl())
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create(connections)))
            .build();
    this.properties = properties;
    this.caches = caches;
  }

  public Customer customer(String id) {
    Customer customer = get("/customers/{id}", id, Customer.class);
    if (!customer.active()) throw new InvalidOrderException("Cliente inactivo: " + id);
    return customer;
  }

  @Cacheable("products")
  public Product product(String id) {
    return get("/products/{id}", id, Product.class);
  }

  public List<Product> products(List<String> ids) {
    Cache cache = caches.getCache("products");
    if (cache == null) throw new IllegalStateException("Caché de productos no configurada");
    Map<String, Product> found = new HashMap<>();
    var missing = new LinkedHashSet<String>();
    for (String id : ids) {
      Product product = cache.get(id, Product.class);
      if (product == null) missing.add(id);
      else found.put(id, product);
    }
    List<String> pending = new ArrayList<>(missing);
    for (int i = 0; i < pending.size(); i += 100) {
      List<String> chunk = pending.subList(i, Math.min(i + 100, pending.size()));
      Set<String> requested = Set.copyOf(chunk);
      List<Product> products =
          send(
              client.post().uri("/products/batch").bodyValue(new BatchRequest(chunk)),
              new ParameterizedTypeReference<List<Product>>() {},
              "productos");
      for (Product product : products) {
        if (product == null || product.id() == null || !requested.contains(product.id())) {
          throw new IllegalStateException("Respuesta inválida del catálogo");
        }
        found.put(product.id(), product);
      }
      if (!found.keySet().containsAll(chunk))
        throw new IllegalStateException("Respuesta incompleta del catálogo");
      for (String id : chunk) cache.put(id, found.get(id));
    }
    return ids.stream().map(found::get).toList();
  }

  private record BatchRequest(List<String> productIds) {}

  private <T> T get(String path, String id, Class<T> type) {
    return send(client.get().uri(path, id), ParameterizedTypeReference.forType(type), id);
  }

  private <T> T send(
      WebClient.RequestHeadersSpec<?> request,
      ParameterizedTypeReference<T> type,
      String description) {
    return request
        .retrieve()
        .onStatus(
            status -> status.value() == 400 || status.value() == 404,
            response ->
                response
                    .releaseBody()
                    .then(
                        Mono.error(
                            new InvalidOrderException(
                                "No se encontró o no es válido: " + description))))
        .bodyToMono(type)
        .switchIfEmpty(
            Mono.error(new IllegalStateException("Respuesta vacía para: " + description)))
        .timeout(properties.requestTimeout())
        .retryWhen(
            Retry.backoff(properties.maxRetries(), properties.retryDelay())
                .filter(this::isTransient)
                .onRetryExhaustedThrow(
                    (spec, signal) ->
                        new CatalogUnavailableException(
                            "No fue posible consultar el catálogo",
                            (int) signal.totalRetries() + 1,
                            signal.failure())))
        .block();
  }

  private boolean isTransient(Throwable error) {
    return error instanceof WebClientRequestException
        || error instanceof TimeoutException
        || (error instanceof WebClientResponseException response
            && (response.getStatusCode().is5xxServerError()
                || response.getStatusCode().value() == 429));
  }
}
