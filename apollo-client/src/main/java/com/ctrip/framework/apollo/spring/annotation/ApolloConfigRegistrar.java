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
package com.ctrip.framework.apollo.spring.annotation;

import com.ctrip.framework.apollo.spring.spi.ApolloConfigRegistrarHelper;
import com.ctrip.framework.foundation.internals.ServiceBootstrap;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
public class ApolloConfigRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

  private final ApolloConfigRegistrarHelper helper = ServiceBootstrap.loadPrimary(ApolloConfigRegistrarHelper.class);

  /**
   * 该扩展点作用是通过自定义的方式直接向容器中注册bean。实现ImportBeanDefinitionRegistrar接口，在重写的registerBeanDefinitions方法中定义的Bean，
   * 就和使用xml中定义Bean效果是一样的。ImportBeanDefinitionRegistrar是Spring框架提供的一种机制，允许通过api代码向容器批量注册BeanDefinition。
   * 它实现了BeanFactoryPostProcessor接口，可以在所有bean定义加载到容器之后，bean实例化之前，对bean定义进行修改。使用ImportBeanDefinitionRegistrar，
   * 我们可以向容器中批量导入bean，而不需要在配置文件中逐个配置。
   * @param importingClassMetadata
   * @param registry
   */
  @Override
  public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
    helper.registerBeanDefinitions(importingClassMetadata, registry);
  }

  /**
   * 凡注册到Spring容器内的bean，实现了EnvironmentAware接口重写setEnvironment方法后，在工程启动时可以获得application.properties的配置文件配置的属性值
   * @param environment
   */
  @Override
  public void setEnvironment(Environment environment) {
    this.helper.setEnvironment(environment);
  }

}
