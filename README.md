# Procesamiento de pedidos

Kafka → worker Java → catálogo Go → MongoDB. Redis guarda bloqueos y mensajes fallidos.

La configuración incluida es para ejecución local; los servicios internos no usan autenticación.

## Ejecutar

```sh
docker compose up --build -d
```

En PowerShell, enviar un pedido de ejemplo:

```powershell
'{"orderId":"order-123","customerId":"customer-456","products":["product-789","product-456"]}' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic pedidos
```

Consultar los pedidos y fallos:

```sh
docker compose exec mongo mongosh pedidos --eval 'db.orders.find().pretty()'
docker compose exec redis redis-cli --scan --pattern 'order:failed:*'
```

El catálogo contiene `customer-456` activo, `customer-789` inactivo, `product-789` y `product-456`.

## Pruebas

```sh
cd catalogo && go test ./...
cd ../worker && mvn test
```

El worker usa un índice único por `orderId`, un bloqueo temporal por pedido y confirma el mensaje tras guardarlo o registrar el fallo en Redis. Las llamadas al catálogo tienen tiempo límite y reintentos configurables. Los productos se conservan en caché por cinco minutos; el estado del cliente se consulta en cada pedido.

Estado: `http://localhost:8080/actuator/health`  
Métricas: `http://localhost:8080/actuator/prometheus`
