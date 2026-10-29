package com.example.shop.clients.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Found by its application name. */
@FeignClient(name = "catalog-service", path = "/api/products")
public interface CatalogClient {

  @GetMapping("/{id}")
  String product(@PathVariable("id") long id);

  @PostMapping
  String create(@RequestBody String product);

  @RequestMapping("/search")
  String search();
}
