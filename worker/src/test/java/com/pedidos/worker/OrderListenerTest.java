package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class OrderListenerTest {
  private final OrderProcessor processor = mock(OrderProcessor.class);
  private final FailedMessageStore failures = mock(FailedMessageStore.class);
  private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
  private final OrderListener listener =
      new OrderListener(new ObjectMapper(), processor, failures, new SimpleMeterRegistry());

  @Test
  void acknowledgesOnlyAfterProcessing() {
    var record =
        new ConsumerRecord<String, String>(
            "pedidos",
            0,
            1L,
            null,
            "{\"orderId\":\"o1\",\"customerId\":\"c1\",\"products\":[\"p1\"]}");
    listener.receive(record, acknowledgment);
    verify(processor).process(any(OrderMessage.class));
    verify(acknowledgment).acknowledge();
    verifyNoInteractions(failures);
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
