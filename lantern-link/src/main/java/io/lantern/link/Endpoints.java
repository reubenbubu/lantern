package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.extract.attributes.EndpointAttributes;
import io.lantern.extract.attributes.WebsocketEndpointAttributes;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;

/**
 * What the matchers read of an endpoint a unit serves, an HTTP {@code endpoint} or a {@code
 * websocket-endpoint}, as their records give it (ADR 0095, amendment for #580).
 *
 * @param httpMethod the HTTP method, if one is mapped
 * @param path the path as served
 * @param controller the controller type's binary name, for an HTTP endpoint
 */
record Endpoints(String httpMethod, String path, String controller) {

  /** The endpoint a fact is; all null for a fact of another type. */
  static Endpoints of(Fact fact) {
    return switch (fact.predicate()) {
      case SpringExtractor.ENDPOINT -> {
        EndpointAttributes a = Claims.of(fact, EndpointAttributes.class);
        yield new Endpoints(a.httpMethod(), a.path(), a.controller());
      }
      case WebSocketExtractor.ENDPOINT -> {
        WebsocketEndpointAttributes a = Claims.of(fact, WebsocketEndpointAttributes.class);
        yield new Endpoints(null, a.path(), null);
      }
      default -> new Endpoints(null, null, null);
    };
  }
}
