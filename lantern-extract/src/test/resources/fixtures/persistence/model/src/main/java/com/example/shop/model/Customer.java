package com.example.shop.model;

import javax.persistence.Entity;
import javax.persistence.Id;

/** The older javax namespace, and an entity name that gives the table its name. */
@Entity(name = "ShopClient")
public class Customer {

  @Id private Long id;
}
