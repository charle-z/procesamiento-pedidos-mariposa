package com.pedidos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class MongoConfigurationTest {
  @Test
  void writesRequestedFieldsWithoutJavaTypeMetadata() {
    var context = new MongoMappingContext();
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    MongoConfiguration.mongoTypeMapper()
        .postProcessBeforeInitialization(converter, "mongoConverter");
    converter.afterPropertiesSet();

    var order =
        new OrderDocument(
            "507f1f77bcf86cd799439011",
            "order-123",
            "customer-456",
            List.of(new OrderDocument.Product("product-789", "Laptop", 999)));
    var document = new Document();
    converter.write(order, document);

    assertInstanceOf(ObjectId.class, document.get("_id"));
    assertEquals("order-123", document.getString("orderId"));
    assertEquals("customer-456", document.getString("customerId"));
    assertFalse(document.containsKey("_class"));
    var products = document.getList("products", Document.class);
    assertEquals("product-789", products.getFirst().getString("productId"));
    assertFalse(products.getFirst().containsKey("_class"));
  }
}
