package com.pedidos.worker;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

@Configuration(proxyBeanMethods = false)
class MongoConfiguration {
  @Bean
  static BeanPostProcessor mongoTypeMapper() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof MappingMongoConverter converter) {
          converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        }
        return bean;
      }
    };
  }
}
