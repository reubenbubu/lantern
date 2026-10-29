package com.example.shop.app;

import com.example.shop.clients.feign.CatalogClient;
import com.example.shop.clients.ratings.RatingsClient;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

  private final CatalogClient catalog;
  private final RatingsClient ratings;

  public OrderService(CatalogClient catalog, RatingsClient ratings) {
    this.catalog = catalog;
    this.ratings = ratings;
  }

  public String place(long productId) {
    return catalog.product(productId) + ratings.rating(productId);
  }
}
