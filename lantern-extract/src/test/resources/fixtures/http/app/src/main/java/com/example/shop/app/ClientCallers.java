package com.example.shop.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Callers of the clients {@link ClientBeans} makes: by qualifier, by name and through a field. */
@Component
public class ClientCallers {

  private final RestClient books;
  private final RestClient trailRestClient;
  private final RestClient quotesRestClient;
  private final RestClient pricingRestClient;
  private final RestClient other;
  private final RestClient lost;

  @Autowired private RestClient fieldRestClient;

  public ClientCallers(
      @Qualifier("booksRestClient") RestClient books,
      RestClient trailRestClient,
      RestClient quotesRestClient,
      RestClient pricingRestClient,
      RestClient other,
      @Qualifier("nobody") RestClient lost) {
    this.books = books;
    this.trailRestClient = trailRestClient;
    this.quotesRestClient = quotesRestClient;
    this.pricingRestClient = pricingRestClient;
    this.other = other;
    this.lost = lost;
  }

  public void book(String id) {
    books.get().uri("/books/{id}", id).retrieve();
  }

  public void trail() {
    trailRestClient.post().uri("/trail").retrieve();
  }

  public void quote() {
    quotesRestClient.get().uri("/quotes").retrieve();
  }

  public void price() {
    pricingRestClient.get().uri("/prices").retrieve();
  }

  public void field() {
    fieldRestClient.get().uri("/field").retrieve();
  }

  /** Falls to the {@code @Primary} bean: no name matches. */
  public void primary() {
    other.get().uri("/primary").retrieve();
  }

  /** The call's own base wins over the bean's. */
  public void overridden() {
    books.mutate().baseUrl("http://override:8080").build().get().uri("/override").retrieve();
  }

  /** No bean has this qualifier: no base. */
  public void lost() {
    lost.get().uri("/lost").retrieve();
  }
}
