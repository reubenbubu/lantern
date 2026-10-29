package com.example.shop.app;

import com.example.shop.clients.exchange.QuotesApi;
import com.example.shop.clients.exchange.ShippingApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Makes the HTTP interfaces' proxies, with the base URLs of the clients behind them. */
@Configuration
public class HttpInterfaces {

  private static final String QUOTES = "http://quotes:8080/v1";

  @Bean
  ShippingApi shippingApi(
      RestClient.Builder builder, @Value("${clients.shipping.url}") String shippingUrl) {
    RestClient restClient = builder.baseUrl(shippingUrl).build();
    HttpServiceProxyFactory factory =
        HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
    return factory.createClient(ShippingApi.class);
  }

  @Bean
  QuotesApi quotesApi() {
    WebClient webClient = WebClient.create(QUOTES);
    return HttpServiceProxyFactory.builder()
        .exchangeAdapter(WebClientAdapter.create(webClient))
        .build()
        .createClient(QuotesApi.class);
  }
}
