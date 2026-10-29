package com.example.shop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

/** Joined inheritance: each subclass has its own table. */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
public class Payment {

  @Id private Long id;
}
