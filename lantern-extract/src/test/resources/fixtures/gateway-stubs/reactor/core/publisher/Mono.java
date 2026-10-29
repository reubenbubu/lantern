package reactor.core.publisher;

public abstract class Mono<T> {
  public static <T> Mono<T> empty() {
    return null;
  }
}
