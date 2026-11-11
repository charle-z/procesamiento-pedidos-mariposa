package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class OrderProcessorTest {
  private final OrderRepository repository = mock(OrderRepository.class);
  private final OrderLock lock = mock(OrderLock.class);
  private final OrderLock.Lease lease = mock(OrderLock.Lease.class);
  private final CatalogClient catalog = mock(CatalogClient.class);
  private final OrderProcessor processor = new OrderProcessor(repository, lock, catalog);
  private final OrderMessage order =
      new OrderMessage("order-123", "customer-456", List.of("product-789"));
  private final OrderDocument stored =
      new OrderDocument(
          null,
          "order-123",
          "customer-456",
          List.of(new OrderDocument.Product("product-789", "Laptop", 999)));

  @BeforeEach
  void prepare() {
    when(lock.acquire("order-123")).thenReturn("token");
    when(lock.maintain("order-123", "token")).thenReturn(lease);
  }

  @Test
  void enrichesAndStoresOrder() {
    when(catalog.customer("customer-456"))
        .thenReturn(new CatalogClient.Customer("customer-456", "Cliente", true));
    when(catalog.products(List.of("product-789")))
        .thenReturn(List.of(new CatalogClient.Product("product-789", "Laptop", "", 999L)));
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
    when(repository.findByOrderId("order-123")).thenReturn(Optional.of(stored));
    processor.process(order);
    verifyNoInteractions(catalog);
    verify(lock).release("order-123", "token");
  }

  @Test
  void sameIdWithDifferentCustomerIsRecordedAsInvalid() {
    when(repository.findByOrderId("order-123")).thenReturn(Optional.of(stored));
    assertThrows(
        InvalidOrderException.class,
        () -> processor.process(new OrderMessage("order-123", "other", List.of("product-789"))));
    verifyNoInteractions(catalog);
    verify(lock).release("order-123", "token");
  }

  @Test
  void sameIdWithDifferentProductsIsRecordedAsInvalid() {
    when(repository.findByOrderId("order-123")).thenReturn(Optional.of(stored));
    assertThrows(
        InvalidOrderException.class,
        () -> processor.process(new OrderMessage("order-123", "customer-456", List.of("other"))));
    verifyNoInteractions(catalog);
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
  void lostLockDoesNotInsertOrder() {
    when(catalog.products(List.of("product-789")))
        .thenReturn(List.of(new CatalogClient.Product("product-789", "Laptop", "", 999L)));
    doThrow(new IllegalStateException("Bloqueo perdido")).when(lease).requireOwnership();
    assertThrows(IllegalStateException.class, () -> processor.process(order));
    verify(repository, never()).insert(any(OrderDocument.class));
    verify(lease).close();
    verify(lock).release("order-123", "token");
  }

  @Test
  void catalogFailureAfterLockLossIsRetriedAsInfrastructureFailure() {
    doThrow(new CatalogUnavailableException("Catálogo no disponible", 3, null))
        .when(catalog)
        .customer("customer-456");
    doThrow(new IllegalStateException("Bloqueo perdido")).when(lease).requireOwnership();
    assertThrows(IllegalStateException.class, () -> processor.process(order));
    verify(repository, never()).insert(any(OrderDocument.class));
    verify(lease).close();
    verify(lock).release("order-123", "token");
  }

  @Test
  void invalidOrderAfterLockLossIsRetriedAsInfrastructureFailure() {
    doThrow(new InvalidOrderException("Cliente inactivo")).when(catalog).customer("customer-456");
    doThrow(new IllegalStateException("Bloqueo perdido")).when(lease).requireOwnership();
    assertThrows(IllegalStateException.class, () -> processor.process(order));
    verify(repository, never()).insert(any(OrderDocument.class));
    verify(lease).close();
    verify(lock).release("order-123", "token");
  }

  @Test
  void uniqueOrderIndexProtectsAgainstConcurrentInsert() {
    when(repository.findByOrderId("order-123")).thenReturn(Optional.empty(), Optional.of(stored));
    when(catalog.products(List.of("product-789")))
        .thenReturn(List.of(new CatalogClient.Product("product-789", "Laptop", "", 999L)));
    doThrow(new DuplicateKeyException("duplicate"))
        .when(repository)
        .insert(any(OrderDocument.class));
    processor.process(order);
    verify(lock).release("order-123", "token");
  }

  @Test
  void concurrentInsertWithDifferentPayloadIsRecordedAsInvalid() {
    var different =
        new OrderDocument(
            null,
            "order-123",
            "other",
            List.of(new OrderDocument.Product("product-789", "Laptop", 999)));
    when(repository.findByOrderId("order-123"))
        .thenReturn(Optional.empty(), Optional.of(different));
    when(catalog.products(List.of("product-789")))
        .thenReturn(List.of(new CatalogClient.Product("product-789", "Laptop", "", 999L)));
    doThrow(new DuplicateKeyException("duplicate"))
        .when(repository)
        .insert(any(OrderDocument.class));
    assertThrows(InvalidOrderException.class, () -> processor.process(order));
    verify(lock).release("order-123", "token");
  }
}
