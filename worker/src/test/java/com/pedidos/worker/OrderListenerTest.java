package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

class OrderListenerTest {
  private final OrderProcessor processor = mock(OrderProcessor.class);
  private final FailedMessageStore failures = mock(FailedMessageStore.class);
  private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final OrderListener listener =
      new OrderListener(JsonMapper.builder().build(), processor, failures, metrics);

  @Test
  void acknowledgesOnlyAfterProcessing() {
    var record =
        new ConsumerRecord<String, String>(
            "pedidos",
            0,
            1L,
            null,
            "{\"orderId\":\"o1\",\"customerId\":\"c1\",\"products\":[\"p1\"]}");
    when(processor.process(any(OrderMessage.class))).thenReturn(true);
    listener.receive(record, acknowledgment);
    verify(processor).process(any(OrderMessage.class));
    verify(acknowledgment).acknowledge();
    verifyNoInteractions(failures);
    assertEquals(1.0, metrics.counter("orders.handled", "result", "processed").count());
  }

  @Test
  void countsDuplicateSeparately() {
    var record =
        new ConsumerRecord<String, String>(
            "pedidos",
            0,
            1L,
            null,
            "{\"orderId\":\"o1\",\"customerId\":\"c1\",\"products\":[\"p1\"]}");
    when(processor.process(any(OrderMessage.class))).thenReturn(false);
    listener.receive(record, acknowledgment);
    verify(acknowledgment).acknowledge();
    assertEquals(1.0, metrics.counter("orders.handled", "result", "duplicate").count());
  }

  @Test
  void savesInvalidMessageBeforeAcknowledging() {
    var record = new ConsumerRecord<String, String>("pedidos", 0, 1L, null, "bad json");
    listener.receive(record, acknowledgment);
    var sequence = inOrder(failures, acknowledgment);
    sequence.verify(failures).save(eq(record), anyString(), eq(1));
    sequence.verify(acknowledgment).acknowledge();
  }

  @Test
  void doesNotAcknowledgeIfFailureCannotBeSaved() {
    var record = new ConsumerRecord<String, String>("pedidos", 0, 1L, null, "bad json");
    doThrow(new IllegalStateException("Redis unavailable"))
        .when(failures)
        .save(eq(record), anyString(), eq(1));
    assertThrows(IllegalStateException.class, () -> listener.receive(record, acknowledgment));
    verifyNoInteractions(acknowledgment);
  }
}
