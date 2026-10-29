package org.springframework.web.service.registry;

import java.util.function.Predicate;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

public interface HttpServiceGroupConfigurer<CB> {
  void configureGroups(Groups<CB> groups);

  interface Groups<CB> {
    Groups<CB> filterByName(String... groupNames);

    Groups<CB> filter(Predicate<HttpServiceGroup> predicate);

    void forEachClient(ClientCallback<CB> callback);

    void forEachProxyFactory(ProxyFactoryCallback callback);

    void forEachGroup(GroupCallback<CB> callback);
  }

  interface ClientCallback<CB> {
    void withClient(HttpServiceGroup group, CB clientBuilder);
  }

  interface ProxyFactoryCallback {
    void withProxyFactory(HttpServiceGroup group, HttpServiceProxyFactory.Builder factoryBuilder);
  }

  interface GroupCallback<CB> {
    void withGroup(
        HttpServiceGroup group, CB clientBuilder, HttpServiceProxyFactory.Builder factoryBuilder);
  }
}
