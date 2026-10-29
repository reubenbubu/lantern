package com.example.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/legacy/items")
public interface LegacyApi {

  @GET
  @Path("/{id}")
  String item(@PathParam("id") long id);
}
