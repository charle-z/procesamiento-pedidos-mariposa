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

  public void process(OrderMessage order) {
    order.validate();
    String token = lock.acquire(order.orderId());
    if (token == null)
      throw new IllegalStateException("Pedido en procesamiento: " + order.orderId());
    try {
      if (repository.existsByOrderId(order.orderId())) return;
      catalog.customer(order.customerId());
      List<OrderDocument.Product> products = new ArrayList<>();
      for (String id : order.products()) {
        CatalogClient.Product product = catalog.product(id);
        products.add(new OrderDocument.Product(product.id(), product.name(), product.price()));
      }
      try {
        repository.insert(new OrderDocument(null, order.orderId(), order.customerId(), products));
      } catch (DuplicateKeyException alreadyProcessed) {
        if (!repository.existsByOrderId(order.orderId())) throw alreadyProcessed;
      }
    } finally {
      lock.release(order.orderId(), token);
    }
  }
}
