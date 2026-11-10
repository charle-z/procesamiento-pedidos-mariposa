package com.pedidos.worker;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("orders")
public record OrderDocument(
    @Id String id,
    @Indexed(unique = true) String orderId,
    String customerId,
    List<Product> products) {
  public record Product(String productId, String name, long price) {}
}
