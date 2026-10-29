package okhttp3;

public final class Request {
  public static class Builder {
    public Builder url(String url) {
      return this;
    }

    public Builder header(String name, String value) {
      return this;
    }

    public Builder post(RequestBody body) {
      return this;
    }

    public Request build() {
      return null;
    }
  }
}
