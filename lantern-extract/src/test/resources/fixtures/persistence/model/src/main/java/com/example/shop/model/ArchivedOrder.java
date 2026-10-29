package com.example.shop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A quoted name is named the same way and stays quoted. */
@Entity
@Table(name = "\"ArchivedOrder\"")
public class ArchivedOrder {

  @Id private Long id;
}
