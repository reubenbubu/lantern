package com.example.shop.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings bound to config, a bean too, which is not a component (#271). */
@Component
@ConfigurationProperties(prefix = "shop.checkout")
public class ShopSettings {
  private int maxItems;

  public int getMaxItems() {
    return maxItems;
  }
}
