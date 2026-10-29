package org.springframework.http;

public final class HttpMethod {
  public static final HttpMethod GET = new HttpMethod();
  public static final HttpMethod POST = new HttpMethod();
  public static final HttpMethod PUT = new HttpMethod();
  public static final HttpMethod PATCH = new HttpMethod();
  public static final HttpMethod DELETE = new HttpMethod();

  private HttpMethod() {}
}
