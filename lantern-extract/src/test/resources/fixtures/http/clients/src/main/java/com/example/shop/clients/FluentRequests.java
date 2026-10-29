package com.example.shop.clients;

import java.io.IOException;
import java.net.URI;
import org.apache.hc.client5.http.fluent.Executor;
import org.apache.hc.client5.http.fluent.Request;
import org.springframework.stereotype.Component;

/** Requests sent with Apache HttpClient's fluent API, 5 and 4. */
@Component
public class FluentRequests {

  public String latest() throws IOException {
    return Request.get("http://quotes:8080/v1/latest").execute().returnContent();
  }

  public void limit() throws IOException {
    Request request = Request.create("PATCH", "http://quotes:8080/v1/limits");
    request.execute();
  }

  public void publish(Executor executor) throws IOException {
    executor.execute(
        Request.post(URI.create("http://quotes:8080/v1/quotes")).bodyString("{}", null));
  }

  /** A request made but not sent here: not a call. */
  public Request draft() {
    return Request.get("http://drafts:8080/v1/draft");
  }

  public String legacy() throws IOException {
    return org.apache.http.client.fluent.Request.Get("http://legacy-quotes/v1/latest")
        .addHeader("Accept", "text/plain")
        .execute()
        .returnContent();
  }
}
