package com.pedidos.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderListener {
  private static final Logger log = LoggerFactory.getLogger(OrderListener.class);
  private final ObjectMapper mapper;
  private final OrderProcessor processor;
  private final FailedMessageStore failures;
  private final MeterRegistry metrics;

  public OrderListener(
      ObjectMapper mapper,
      OrderProcessor processor,
      FailedMessageStore failures,
      MeterRegistry metrics) {
    this.mapper = mapper;
    this.processor = processor;
    this.failures = failures;
    this.metrics = metrics;
  }

  @KafkaListener(topics = "${pedidos.topic}")
  public void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    Timer.Sample sample = Timer.start(metrics);
    try {
      handle(record, acknowledgment);
    } finally {
      sample.stop(metrics.timer("orders.processing"));
    }
  }

  private void handle(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    String result = "processed";
    try {
      if (record.value() == null) throw new InvalidOrderException("El mensaje no puede ser nulo");
      OrderMessage order = mapper.readValue(record.value(), OrderMessage.class);
      if (order == null) throw new InvalidOrderException("El pedido no puede ser nulo");
      boolean created = processor.process(order);
      result = created ? "processed" : "duplicate";
      log.info("Pedido {}: {}", result, order.orderId());
    } catch (JsonProcessingException | InvalidOrderException invalid) {
      failures.save(record, invalid.getMessage(), 1);
      result = "invalid";
      log.warn("Pedido inválido en partición {} offset {}", record.partition(), record.offset());
    } catch (CatalogUnavailableException unavailable) {
      failures.save(record, unavailable.getMessage(), unavailable.attempts());
      result = "catalog_unavailable";
      log.warn("Catálogo no disponible tras {} intentos", unavailable.attempts());
    }
    acknowledgment.acknowledge();
    metrics.counter("orders.handled", "result", result).increment();
  }
}
