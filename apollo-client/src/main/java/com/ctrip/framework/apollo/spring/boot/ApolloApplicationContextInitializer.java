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
package com.ctrip.framework.apollo.spring.boot;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.ApolloClientSystemConsts;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.utils.DeferredLogger;
import com.ctrip.framework.apollo.spring.config.CachedCompositePropertySource;
import com.ctrip.framework.apollo.spring.config.ConfigPropertySourceFactory;
import com.ctrip.framework.apollo.spring.config.PropertySourcesConstants;
import com.ctrip.framework.apollo.spring.util.PropertySourcesUtil;
import com.ctrip.framework.apollo.spring.util.SpringInjector;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Initialize apollo system properties and inject the Apollo config in Spring Boot bootstrap phase
 *
 * <p>Configuration example:</p>
 * <pre class="code">
 *   # set app.id
 *   app.id = 100004458
 *   # enable apollo bootstrap config and inject 'application' namespace in bootstrap phase
 *   apollo.bootstrap.enabled = true
 * </pre>
 *
 * or
 *
 * <pre class="code">
 *   # set app.id
 *   app.id = 100004458
 *   # enable apollo bootstrap config
 *   apollo.bootstrap.enabled = true
 *   # will inject 'application' and 'FX.apollo' namespaces in bootstrap phase
 *   apollo.bootstrap.namespaces = application,FX.apollo
 * </pre>
 *
 *
 * If you want to load Apollo configurations even before Logging System Initialization Phase,
 *  add
 * <pre class="code">
 *   # set apollo.bootstrap.eagerLoad.enabled
 *   apollo.bootstrap.eagerLoad.enabled = true
 * </pre>
 *
 *  This would be very helpful when your logging configurations is set by Apollo.
 *
 *  for example, you have defined logback-spring.xml in your project, and you want to inject some attributes into logback-spring.xml.
 *
 */
public class ApolloApplicationContextInitializer implements
    ApplicationContextInitializer<ConfigurableApplicationContext> , EnvironmentPostProcessor, Ordered {
  public static final int DEFAULT_ORDER = 0;

  private static final Logger logger = LoggerFactory.getLogger(ApolloApplicationContextInitializer.class);
  private static final Splitter NAMESPACE_SPLITTER = Splitter.on(",").omitEmptyStrings()
      .trimResults();
  public static final String[] APOLLO_SYSTEM_PROPERTIES = {ApolloClientSystemConsts.APP_ID,
      ApolloClientSystemConsts.APOLLO_LABEL,
      ApolloClientSystemConsts.APOLLO_CLUSTER,
      ApolloClientSystemConsts.APOLLO_CACHE_DIR,
      ApolloClientSystemConsts.APOLLO_ACCESS_KEY_SECRET,
      ApolloClientSystemConsts.APOLLO_META,
      ApolloClientSystemConsts.APOLLO_CONFIG_SERVICE,
      ApolloClientSystemConsts.APOLLO_PROPERTY_ORDER_ENABLE,
      ApolloClientSystemConsts.APOLLO_PROPERTY_NAMES_CACHE_ENABLE,
      ApolloClientSystemConsts.APOLLO_OVERRIDE_SYSTEM_PROPERTIES};

  private final ConfigPropertySourceFactory configPropertySourceFactory = SpringInjector
      .getInstance(ConfigPropertySourceFactory.class);

  private int order = DEFAULT_ORDER;

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    ConfigurableEnvironment environment = context.getEnvironment();

    // 判断是否配置了 apollo.bootstrap.enabled=true
    if (!environment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED, Boolean.class, false)) {
      logger.debug("Apollo bootstrap config is not enabled for context {}, see property: ${{}}", context, PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED);
      return;
    }
    logger.debug("Apollo bootstrap config is enabled for context {}", context);

    // 初始化，内部会加载远端Apollo Server 配置
    initialize(environment);
  }


  /**
   * 初始化Apollo配置
   *
   * @param environment
   */
  protected void initialize(ConfigurableEnvironment environment) {
    final ConfigUtil configUtil = ApolloInjector.getInstance(ConfigUtil.class);
    if (environment.getPropertySources().contains(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME)) {
      // 已经初始化，重播日志系统初始化之前打印的日志
      DeferredLogger.replayTo();
      if (configUtil.isOverrideSystemProperties()) {
        // 确保ApolloBootstrapPropertySources仍然是第一个，如果不是会将其调整为第一个，这样从Apollo加载出来的配置拥有更高优先级
        PropertySourcesUtil.ensureBootstrapPropertyPrecedence(environment);
      }
      // 因为有两个不同的触发点，所以该方法首先检查 Spring 的 Environment 环境中是否已经有了 key 为 ApolloBootstrapPropertySources 的目标属性，有的话就不必往下处理，直接 return
      return;
    }

    // 获取配置的命名空间参数
    String namespaces = environment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_NAMESPACES, ConfigConsts.NAMESPACE_APPLICATION);
    logger.debug("Apollo bootstrap namespaces: {}", namespaces);
    // 使用","切分命名参数
    List<String> namespaceList = NAMESPACE_SPLITTER.splitToList(namespaces);

    // 创建 `CompositePropertySource` 复合属性源，因为 apollo-client 启动时可以加载多个命名空间的配置，每个namespace对应一个 `PropertySource`，
    // 而多个 `PropertySource` 就会被封装在 `CompositePropertySource` 对象中，若需要获取apollo中配置的属性时，就会遍历多个命名空间所对应的 `PropertySource`，
    // 找到对应属性后就会直接返回，这也意味着，先加载的namespace配置具有更高优先级
    CompositePropertySource composite;
    if (configUtil.isPropertyNamesCacheEnabled()) {
      composite = new CachedCompositePropertySource(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME);
    } else {
      composite = new CompositePropertySource(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME);
    }
    for (String namespace : namespaceList) {
      // 从远端拉去命名空间对应的配置
      Config config = ConfigService.getConfig(namespace);
      // 调用ConfigPropertySourceFactory#getConfigPropertySource() 缓存从远端拉取的配置，并将其包装为 PropertySource，
      // 最终将所有拉取到的远端配置聚合到一个以 ApolloBootstrapPropertySources 为 key 的属性源包装类 CompositePropertySource 的内部
      composite.addPropertySource(configPropertySourceFactory.getConfigPropertySource(namespace, config));
    }
    if (!configUtil.isOverrideSystemProperties()) {
      if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
        environment.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, composite);
        return;
      }
    }
    // 将 CompositePropertySource 属性源包装类添加到 Spring 的 Environment 环境中，注意是插入在属性源列表的头部，
    // 因为取属性的时候其实是遍历这个属性源列表来查找，找到即返回，所以出现同名属性时，前面的优先级更高
    environment.getPropertySources().addFirst(composite);
  }

  /**
   * To fill system properties from environment config
   */
  void initializeSystemProperty(ConfigurableEnvironment environment) {
    for (String propertyName : APOLLO_SYSTEM_PROPERTIES) {
      fillSystemPropertyFromEnvironment(environment, propertyName);
    }
  }

  private void fillSystemPropertyFromEnvironment(ConfigurableEnvironment environment, String propertyName) {
    if (System.getProperty(propertyName) != null) {
      return;
    }

    String propertyValue = environment.getProperty(propertyName);

    if (Strings.isNullOrEmpty(propertyValue)) {
      return;
    }

    System.setProperty(propertyName, propertyValue);
  }

  /**
   *
   * In order to load Apollo configurations as early as even before Spring loading logging system phase,
   * this EnvironmentPostProcessor can be called Just After ConfigFileApplicationListener has succeeded.
   * 为了早在Spring加载日志系统阶段之前就加载Apollo配置，这个EnvironmentPostProcessor可以在ConfigFileApplicationListener成功之后调用。
   * 处理顺序是这样的: 加载Bootstrap属性和应用程序属性----->加载Apollo配置属性---->初始化日志系
   * <br />
   * The processing sequence would be like this: <br />
   * Load Bootstrap properties and application properties -----> load Apollo configuration properties ----> Initialize Logging systems
   *
   * @param configurableEnvironment
   * @param springApplication
   */
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment configurableEnvironment, SpringApplication springApplication) {

    // should always initialize system properties like app.id in the first place
    initializeSystemProperty(configurableEnvironment);

    // 获取 apollo.bootstrap.eagerLoad.enabled 配置
    Boolean eagerLoadEnabled = configurableEnvironment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_EAGER_LOAD_ENABLED, Boolean.class, false);

    //EnvironmentPostProcessor should not be triggered if you don't want Apollo Loading before Logging System Initialization
    if (!eagerLoadEnabled) {
      // 如果未开启提前加载，则 postProcessEnvironment 扩展点直接返回，不加载配置
      return;
    }

    // 是否开启了 apollo.bootstrap.enabled 参数，只有开启了才会在Spring启动阶段加载配置
    Boolean bootstrapEnabled = configurableEnvironment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED, Boolean.class, false);

    if (bootstrapEnabled) {
      DeferredLogger.enable();
      // 执行初始化操作，内部会加载Apollo Server配置
      initialize(configurableEnvironment);
    }

  }

  /**
   * @since 1.3.0
   */
  @Override
  public int getOrder() {
    return order;
  }

  /**
   * @since 1.3.0
   */
  public void setOrder(int order) {
    this.order = order;
  }

}
