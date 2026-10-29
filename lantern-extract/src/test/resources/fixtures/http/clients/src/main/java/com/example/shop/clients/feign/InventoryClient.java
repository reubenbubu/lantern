package com.example.shop.clients.feign;

import feign.RequestLine;
import org.springframework.cloud.openfeign.FeignClient;

/** At a configured URL, with Feign's own annotations. */
@FeignClient(name = "inventory", url = "${inventory.url}")
public interface InventoryClient {

  @RequestLine("DELETE /stock/{sku}?force=true")
  void release(String sku);
}
