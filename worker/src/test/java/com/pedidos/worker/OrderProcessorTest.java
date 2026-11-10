package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class OrderProcessorTest {
  private final OrderRepository repository = mock(OrderRepository.class);
  private final OrderLock lock = mock(OrderLock.class);
  private final CatalogClient catalog = mock(CatalogClient.class);
  private final OrderProcessor processor = new OrderProcessor(repository, lock, catalog);
  private final OrderMessage order =
      new OrderMessage("order-123", "customer-456", List.of("product-789"));

  @BeforeEach
  void prepare() {
    when(lock.acquire("order-123")).thenReturn("token");
  }

  @Test
  void enrichesAndStoresOrder() {
    when(catalog.customer("customer-456"))
        .thenReturn(new CatalogClient.Customer("customer-456", "Cliente", true));
    when(catalog.product("product-789"))
        .thenReturn(new CatalogClient.Product("product-789", "Laptop", "", 999));
    processor.process(order);
    verify(repository)
        .insert(
            new OrderDocument(
                null,
                "order-123",
                "customer-456",
                List.of(new OrderDocument.Product("product-789", "Laptop", 999))));
    verify(lock).release("order-123", "token");
  }

  @Test
  void alreadyProcessedDoesNotCallCatalog() {
    when(repository.existsByOrderId("order-123")).thenReturn(true);
    processor.process(order);
    verifyNoInteractions(catalog);
    verify(lock).release("order-123", "token");
  }

  @Test
  void busyLockIsRetriedRatherThanAcknowledged() {
    when(lock.acquire("order-123")).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> processor.process(order));
    verifyNoInteractions(repository, catalog);
    verify(lock, never()).release(any(), any());
  }

  @Test
  void invalidCustomerDoesNotStoreOrder() {
    doThrow(new InvalidOrderException("Cliente inactivo")).when(catalog).customer("customer-456");
    assertThrows(InvalidOrderException.class, () -> processor.process(order));
    verify(repository, never()).insert(any(OrderDocument.class));
    verify(lock).release("order-123", "token");
  }

  @Test
  void uniqueOrderIndexProtectsAgainstConcurrentInsert() {
    when(repository.existsByOrderId("order-123")).thenReturn(false, true);
    when(catalog.product("product-789"))
        .thenReturn(new CatalogClient.Product("product-789", "Laptop", "", 999));
    doThrow(new DuplicateKeyException("duplicate"))
        .when(repository)
        .insert(any(OrderDocument.class));
    processor.process(order);
    verify(lock).release("order-123", "token");
  }
}
