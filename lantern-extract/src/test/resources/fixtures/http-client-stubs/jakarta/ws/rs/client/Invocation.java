package jakarta.ws.rs.client;

public interface Invocation {
  interface Builder {
    <T> T get(Class<T> type);

    <T> T method(String name, Class<T> type);
  }
}
