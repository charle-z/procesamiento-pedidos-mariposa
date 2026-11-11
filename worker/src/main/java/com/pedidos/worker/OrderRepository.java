package com.pedidos.worker;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<OrderDocument, String> {
  Optional<OrderDocument> findByOrderId(String orderId);
}
