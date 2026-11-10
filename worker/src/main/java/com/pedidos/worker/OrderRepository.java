package com.pedidos.worker;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<OrderDocument, String> {
  boolean existsByOrderId(String orderId);
}
