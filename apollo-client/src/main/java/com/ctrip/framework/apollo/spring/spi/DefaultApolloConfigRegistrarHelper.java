/*
 * Copyright 2022 Apollo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.ctrip.framework.apollo.spring.spi;

import com.ctrip.framework.apollo.core.spi.Ordered;
import com.ctrip.framework.apollo.spring.annotation.ApolloAnnotationProcessor;
import com.ctrip.framework.apollo.spring.annotation.EnableApolloConfig;
import com.ctrip.framework.apollo.spring.annotation.SpringValueProcessor;
import com.ctrip.framework.apollo.spring.config.PropertySourcesProcessor;
import com.ctrip.framework.apollo.spring.property.AutoUpdateConfigChangeListener;
import com.ctrip.framework.apollo.spring.property.SpringValueDefinitionProcessor;
import com.ctrip.framework.apollo.spring.util.BeanRegistrationUtil;
import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

public class DefaultApolloConfigRegistrarHelper implements ApolloConfigRegistrarHelper {
  private static final Logger logger = LoggerFactory.getLogger(
      DefaultApolloConfigRegistrarHelper.class);

  private Environment environment;

  @Override
  public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
    AnnotationAttributes attributes = AnnotationAttributes
        .fromMap(importingClassMetadata.getAnnotationAttributes(EnableApolloConfig.class.getName()));
    final String[] namespaces = attributes.getStringArray("value");
    final int order = attributes.getNumber("order");
    final String[] resolvedNamespaces = this.resolveNamespaces(namespaces);
    PropertySourcesProcessor.addNamespaces(Lists.newArrayList(resolvedNamespaces), order);

    Map<String, Object> propertySourcesPlaceholderPropertyValues = new HashMap<>();
    // to make sure the default PropertySourcesPlaceholderConfigurer's priority is higher than PropertyPlaceholderConfigurer
    propertySourcesPlaceholderPropertyValues.put("order", 0);

    // PropertySourcesPlaceholderConfigurer是 SpringBoot 框架自身的占位符处理配置，占位符的处理主要是将 ${apollo.value} 这样的字符串解析出 关键字 apollo.value，再使用这个 key 通过 PropertySourcesPropertyResolver 从 PropertySource 中找到对应的属性值替换掉占位符
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, PropertySourcesPlaceholderConfigurer.class,
            propertySourcesPlaceholderPropertyValues);
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, AutoUpdateConfigChangeListener.class);
    // 用于拉取 @EnableApolloConfig 配置的 namespace 的远程配置
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, PropertySourcesProcessor.class);
    // 用于处理 Apollo 的专用注解
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, ApolloAnnotationProcessor.class);
    // 用于处理 @Value 注解标注的类成员变量和对象方法
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, SpringValueProcessor.class);
    // 用于处理 XML 文件中的占位符
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, SpringValueDefinitionProcessor.class);
  }

  private String[] resolveNamespaces(String[] namespaces) {
    // no support for Spring version prior to 3.2.x, see https://github.com/apolloconfig/apollo/issues/4178
    if (this.environment == null) {
      logNamespacePlaceholderNotSupportedMessage(namespaces);
      return namespaces;
    }
    String[] resolvedNamespaces = new String[namespaces.length];
    for (int i = 0; i < namespaces.length; i++) {
      // throw IllegalArgumentException if given text is null or if any placeholders are unresolvable
      resolvedNamespaces[i] = this.environment.resolveRequiredPlaceholders(namespaces[i]);
    }
    return resolvedNamespaces;
  }

  private void logNamespacePlaceholderNotSupportedMessage(String[] namespaces) {
    for (String namespace : namespaces) {
      if (namespace.contains("${")) {
        logger.warn("Namespace placeholder {} is not supported for Spring version prior to 3.2.x,"
                + " see https://github.com/apolloconfig/apollo/issues/4178 for more details.",
            namespace);
        break;
      }
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }
}