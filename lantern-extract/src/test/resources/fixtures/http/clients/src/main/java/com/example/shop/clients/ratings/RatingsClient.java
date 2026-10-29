package com.example.shop.clients.ratings;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "ratings")
@Path("/ratings")
public interface RatingsClient {

  @GET
  @Path("/{id}")
  String rating(@PathParam("id") long id);
}
