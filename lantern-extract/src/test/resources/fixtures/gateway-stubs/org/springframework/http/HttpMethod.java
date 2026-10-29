package org.springframework.http;

public final class HttpMethod {
  public static final HttpMethod GET = new HttpMethod();
  public static final HttpMethod POST = new HttpMethod();

  private HttpMethod() {}
}
