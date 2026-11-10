package com.pedidos.worker;

public class CatalogUnavailableException extends RuntimeException {
  private final int attempts;

  public CatalogUnavailableException(String message, int attempts, Throwable cause) {
    super(message, cause);
    this.attempts = attempts;
  }

  public int attempts() {
    return attempts;
  }
}
