package com.example.shop.clients;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequests;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.classic.methods.ClassicHttpRequests;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.RequestBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Requests built as objects, then sent: java.net.http, OkHttp and Apache HttpClient. */
@Component
public class RawRequests {

  @Value("${clients.ledger.url}")
  private String ledgerUrl;

  public HttpRequest entry(String id) {
    return HttpRequest.newBuilder(URI.create(ledgerUrl + "/entries/" + id))
        .header("Accept", "application/json")
        .build();
  }

  public HttpRequest book() {
    HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(ledgerUrl + "/entries"));
    return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
  }

  public Request notify(RequestBody body) {
    return new Request.Builder().url("http://notify:8080/notifications").post(body).build();
  }

  public HttpPost report() {
    HttpPost post = new HttpPost("http://reports/api/reports?draft=true");
    post.setHeader("Accept", "application/json");
    return post;
  }

  public ClassicHttpRequest archive() {
    return ClassicRequestBuilder.put("http://archive:8080/api/archive").build();
  }

  public SimpleHttpRequest evict(String key) {
    return SimpleHttpRequest.create("DELETE", "http://cache:6000/api/cache/" + key);
  }

  public HttpRequest amend() {
    return HttpRequest.newBuilder(URI.create("http://ledger:8080/entries"))
        .method("PATCH", HttpRequest.BodyPublishers.noBody())
        .build();
  }

  public Request replace(RequestBody body) {
    return new Request.Builder()
        .url(HttpUrl.parse("http://notify:8080/templates"))
        .method("PUT", body)
        .build();
  }

  public Request status() {
    return new Request.Builder().url(HttpUrl.get("http://notify:8080/status")).build();
  }

  public Request legacyStatus() throws MalformedURLException {
    return new Request.Builder().url(new URL("http://notify:8080/legacy/status")).build();
  }

  public Object reports() {
    return RequestBuilder.get("http://reports/api/reports").build();
  }

  public Object purge() {
    return RequestBuilder.create("DELETE").setUri(URI.create("http://reports/api/drafts")).build();
  }

  public SimpleHttpRequest warm() {
    return SimpleRequestBuilder.post("http://cache:6000/api/warm").build();
  }

  public HttpGet archived() {
    return new HttpGet("http://archive:8080/api/archive/{id}");
  }

  public Object archiveIndex() {
    return ClassicHttpRequests.get("http://archive:8080/api/index");
  }

  public SimpleHttpRequest probe() {
    return SimpleHttpRequests.create("HEAD", "http://cache:6000/api/cache");
  }
}
