package com.example.shop.web.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ResponseBody
public class CatalogController implements CatalogApi {

  @Override
  public String products() {
    return "products";
  }
}
