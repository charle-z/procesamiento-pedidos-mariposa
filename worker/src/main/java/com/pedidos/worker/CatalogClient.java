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
import org.springframework.core.codec.DecodingException;
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
  public record Customer(String id, String name, Boolean active) {}

  public record Product(String id, String name, String description, Long price) {}

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
    if (!id.equals(customer.id()) || customer.active() == null) throw invalidResponse("cliente");
    if (!customer.active()) throw new InvalidOrderException("Cliente inactivo: " + id);
    return customer;
  }

  @Cacheable("products")
  public Product product(String id) {
    Product product = get("/products/{id}", id, Product.class);
    if (!id.equals(product.id()) || product.name() == null || product.price() == null)
      throw invalidResponse("producto");
    return product;
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
      if (products.size() != chunk.size()) throw invalidResponse("productos");
      Set<String> returned = new java.util.HashSet<>();
      for (Product product : products) {
        if (product == null
            || product.id() == null
            || product.name() == null
            || product.price() == null
            || !requested.contains(product.id())
            || !returned.add(product.id())) {
          throw invalidResponse("productos");
        }
        found.put(product.id(), product);
      }
      if (!found.keySet().containsAll(chunk)) throw invalidResponse("productos");
      for (String id : chunk) cache.put(id, found.get(id));
    }
    return ids.stream().map(found::get).toList();
  }

  private record BatchRequest(List<String> productIds) {}

  private CatalogUnavailableException invalidResponse(String description) {
    return new CatalogUnavailableException(
        "Respuesta inválida del catálogo: " + description, 1, null);
  }

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
        .onStatus(
            status -> status.is4xxClientError() && status.value() != 408 && status.value() != 429,
            response ->
                response
                    .releaseBody()
                    .then(
                        Mono.error(
                            new CatalogUnavailableException(
                                "El catálogo rechazó la consulta", 1, null))))
        .bodyToMono(type)
        .onErrorMap(
            DecodingException.class,
            error ->
                new CatalogUnavailableException(
                    "Respuesta inválida del catálogo: " + description, 1, error))
        .switchIfEmpty(Mono.error(invalidResponse(description)))
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
                || response.getStatusCode().value() == 408
                || response.getStatusCode().value() == 429));
  }
}
