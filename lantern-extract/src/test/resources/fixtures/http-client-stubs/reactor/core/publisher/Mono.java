package reactor.core.publisher;

public abstract class Mono<T> {
  public abstract T block();
}
