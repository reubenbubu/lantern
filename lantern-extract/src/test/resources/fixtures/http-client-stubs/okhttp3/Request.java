package okhttp3;

import java.net.URL;

public final class Request {
  public static class Builder {
    public Builder url(String url) {
      return this;
    }

    public Builder url(HttpUrl url) {
      return this;
    }

    public Builder url(URL url) {
      return this;
    }

    public Builder header(String name, String value) {
      return this;
    }

    public Builder post(RequestBody body) {
      return this;
    }

    public Builder method(String method, RequestBody body) {
      return this;
    }

    public Request build() {
      return null;
    }
  }
}
