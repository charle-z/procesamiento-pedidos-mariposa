package com.pedidos.worker;

import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderProcessor {
  private final OrderRepository repository;
  private final OrderLock lock;
  private final CatalogClient catalog;

  public OrderProcessor(OrderRepository repository, OrderLock lock, CatalogClient catalog) {
    this.repository = repository;
    this.lock = lock;
    this.catalog = catalog;
  }

  public boolean process(OrderMessage order) {
    order.validate();
    String token = lock.acquire(order.orderId());
    if (token == null)
      throw new IllegalStateException("Pedido en procesamiento: " + order.orderId());
    try {
      try (var lease = lock.maintain(order.orderId(), token)) {
        try {
          return processLocked(order, lease);
        } catch (InvalidOrderException | CatalogUnavailableException failure) {
          lease.requireOwnership();
          throw failure;
        }
      }
    } finally {
      lock.release(order.orderId(), token);
    }
  }

  private boolean processLocked(OrderMessage order, OrderLock.Lease lease) {
    var previous = repository.findByOrderId(order.orderId());
    if (previous.isPresent()) {
      verifyDuplicate(order, previous.get());
      lease.requireOwnership();
      return false;
    }
    catalog.customer(order.customerId());
    List<OrderDocument.Product> products = new ArrayList<>();
    for (CatalogClient.Product product : catalog.products(order.products())) {
      products.add(new OrderDocument.Product(product.id(), product.name(), product.price()));
    }
    try {
      lease.requireOwnership();
      repository.insert(new OrderDocument(null, order.orderId(), order.customerId(), products));
      return true;
    } catch (DuplicateKeyException alreadyProcessed) {
      var existing = repository.findByOrderId(order.orderId());
      if (existing.isEmpty()) throw alreadyProcessed;
      verifyDuplicate(order, existing.get());
      lease.requireOwnership();
      return false;
    }
  }

  private void verifyDuplicate(OrderMessage order, OrderDocument existing) {
    List<String> productIds =
        existing.products().stream().map(OrderDocument.Product::productId).toList();
    if (!existing.customerId().equals(order.customerId()) || !productIds.equals(order.products())) {
      throw new InvalidOrderException("El identificador del pedido ya existe con otros datos");
    }
  }
}
