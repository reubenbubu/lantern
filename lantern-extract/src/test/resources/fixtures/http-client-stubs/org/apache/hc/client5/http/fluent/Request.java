package org.apache.hc.client5.http.fluent;

import java.net.URI;

public class Request {
  public static Request get(String uri) {
    return null;
  }

  public static Request post(URI uri) {
    return null;
  }

  public static Request create(String method, String uri) {
    return null;
  }

  public Request bodyString(String body, Object contentType) {
    return this;
  }

  public Response execute() {
    return null;
  }
}
