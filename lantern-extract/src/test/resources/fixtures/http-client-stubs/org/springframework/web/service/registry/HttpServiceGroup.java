package org.springframework.web.service.registry;

public interface HttpServiceGroup {
  String DEFAULT_GROUP_NAME = "default";

  String name();

  enum ClientType {
    REST_CLIENT,
    WEB_CLIENT,
    UNSPECIFIED
  }
}
