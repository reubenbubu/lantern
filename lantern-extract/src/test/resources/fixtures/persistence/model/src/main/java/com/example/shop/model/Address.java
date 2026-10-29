package com.example.shop.model;

import jakarta.persistence.Embeddable;

/** Embeddable, not an entity: no table of its own. */
@Embeddable
public class Address {

  private String street;
}
