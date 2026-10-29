package com.example.shop.web.legacy;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("legacy/orders")
public class LegacyResource {

  @GET
  @Path("{id}")
  public String get(@PathParam("id") long id) {
    return "order";
  }

  @POST
  public void create(String order) {}

  @Path("lines")
  public Object lines() {
    return null;
  }
}
