package com.pedidos.worker;

import java.util.List;

public record OrderMessage(String orderId, String customerId, List<String> products) {
  public void validate() {
    if (orderId == null
        || orderId.isBlank()
        || customerId == null
        || customerId.isBlank()
        || products == null
        || products.isEmpty()
        || products.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new InvalidOrderException(
          "El pedido debe incluir identificadores de pedido, cliente y productos");
    }
  }
}
