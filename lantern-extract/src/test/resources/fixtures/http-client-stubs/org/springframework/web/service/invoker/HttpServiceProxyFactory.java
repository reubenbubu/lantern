package org.springframework.web.service.invoker;

public final class HttpServiceProxyFactory {
  public static Builder builderFor(HttpExchangeAdapter adapter) {
    return null;
  }

  public static Builder builder() {
    return null;
  }

  public static Builder builder(HttpExchangeAdapter adapter) {
    return null;
  }

  public <S> S createClient(Class<S> serviceType) {
    return null;
  }

  public static final class Builder {
    public Builder exchangeAdapter(HttpExchangeAdapter adapter) {
      return this;
    }

    public HttpServiceProxyFactory build() {
      return null;
    }
  }
}
