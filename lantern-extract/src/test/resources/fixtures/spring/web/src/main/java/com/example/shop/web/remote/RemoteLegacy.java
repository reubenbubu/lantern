package com.example.shop.web.remote;

import com.example.api.LegacyApi;

/** A Jakarta REST resource whose @Path is on the interface it implements. */
public class RemoteLegacy implements LegacyApi {

  @Override
  public String item(long id) {
    return "item";
  }
}
