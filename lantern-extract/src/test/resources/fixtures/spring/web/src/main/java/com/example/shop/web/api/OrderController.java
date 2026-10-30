package com.example.shop.web.api;

import com.example.shop.web.secure.Secured;
import org.springframework.web.bind.annotation.*;

@WebApi
@RequestMapping("${shop.api}/orders")
public class OrderController {

  @PostMapping
  @Secured("orders:create")
  public long place(@RequestBody String order) {
    return 1;
  }

  @GetMapping("/{id}")
  public String order(@PathVariable long id) {
    return "order";
  }

  @RequestMapping(
      path = "/{id}/lines",
      method = {RequestMethod.PUT, RequestMethod.PATCH})
  public void lines(@PathVariable long id) {}

  @GetMapping(Paths.STATUS)
  public String status() {
    return "ok";
  }

  @GetMapping("/token/${shop.api-token}")
  public String token() {
    return "hidden";
  }

  public String notMapped() {
    return "";
  }
}
