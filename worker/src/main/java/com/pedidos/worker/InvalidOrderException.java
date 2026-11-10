package com.pedidos.worker;

public class InvalidOrderException extends RuntimeException {
  public InvalidOrderException(String message) {
    super(message);
  }
}
