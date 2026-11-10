package com.pedidos.worker;

import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class CatalogClient {
  public record Customer(String id, String name, boolean active) {}

  public record Product(String id, String name, String description, long price) {}

  private final WebClient client;
  private final OrderProperties properties;

  public CatalogClient(WebClient.Builder builder, OrderProperties properties) {
    this.client = builder.baseUrl(properties.catalogUrl()).build();
    this.properties = properties;
  }

  public Customer customer(String id) {
    Customer customer = get("/customers/{id}", id, Customer.class);
    if (!customer.active()) throw new InvalidOrderException("Cliente inactivo: " + id);
    return customer;
  }

  public Product product(String id) {
    return get("/products/{id}", id, Product.class);
  }

  private <T> T get(String path, String id, Class<T> type) {
    return client
        .get()
        .uri(path, id)
        .retrieve()
        .onStatus(
            HttpStatusCode::is4xxClientError,
            response ->
                response
                    .releaseBody()
                    .then(
                        Mono.error(
                            new InvalidOrderException("No se encontró o no es válido: " + id))))
        .bodyToMono(type)
        .switchIfEmpty(Mono.error(new InvalidOrderException("Respuesta vacía para: " + id)))
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
            && response.getStatusCode().is5xxServerError());
  }
}
