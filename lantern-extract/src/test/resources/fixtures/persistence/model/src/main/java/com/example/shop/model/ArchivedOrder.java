package com.example.shop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A quoted name keeps its case. */
@Entity
@Table(name = "\"ArchivedOrder\"")
public class ArchivedOrder {

  @Id private Long id;
}
