package com.example.shop.app;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Callers of the clients {@link ClientBeans} makes: by qualifier, by name and through a field. */
@Component
public class ClientCallers {

  private final RestClient books;
  private final RestClient trailRestClient;
  private final RestClient quotesRestClient;

  public ClientCallers(
      @Qualifier("booksRestClient") RestClient books,
      RestClient trailRestClient,
      RestClient quotesRestClient) {
    this.books = books;
    this.trailRestClient = trailRestClient;
    this.quotesRestClient = quotesRestClient;
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
}
