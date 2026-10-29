package com.example.shop.app;

import com.example.shop.clients.exchange.InventoryApi;
import com.example.shop.clients.exchange.LedgerApi;
import com.example.shop.clients.exchange.PricesApi;
import com.example.shop.clients.exchange.QuotesApi;
import com.example.shop.clients.exchange.ShippingApi;
import com.example.shop.clients.exchange.StockApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.client.support.RestTemplateAdapter;
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
    WebClient webClient = WebClient.create(QUOTES + "/" + region());
    return HttpServiceProxyFactory.builder()
        .exchangeAdapter(WebClientAdapter.create(webClient))
        .build()
        .createClient(QuotesApi.class);
  }

  @Bean
  LedgerApi ledgerApi(RestTemplateBuilder builder) {
    RestTemplate restTemplate = builder.rootUri("http://ledger-api:8080").build();
    return HttpServiceProxyFactory.builderFor(RestTemplateAdapter.create(restTemplate))
        .build()
        .createClient(LedgerApi.class);
  }

  @Bean
  PricesApi pricesApi(WebClient.Builder builder) {
    WebClient webClient = builder.baseUrl("http://prices:8080").build();
    return HttpServiceProxyFactory.builder(WebClientAdapter.forClient(webClient))
        .build()
        .createClient(PricesApi.class);
  }

  @Bean
  HttpServiceProxyFactory inventoryFactory(
      RestClient.Builder builder, @Value("${clients.inventory.url}") String inventoryUrl) {
    RestClient restClient = builder.baseUrl(inventoryUrl).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
  }

  @Bean
  InventoryApi inventoryApi(HttpServiceProxyFactory factory) {
    return factory.createClient(InventoryApi.class);
  }

  @Bean
  RestClientAdapter stockAdapter(RestClient.Builder builder) {
    return RestClientAdapter.create(builder.baseUrl("http://stock:8080/api").build());
  }

  @Bean
  StockApi stockApi(RestClientAdapter stockAdapter) {
    return HttpServiceProxyFactory.builderFor(stockAdapter).build().createClient(StockApi.class);
  }

  private static String region() {
    return "eu";
  }
}
