package com.example.shop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Single-table inheritance, the default: subclasses share the table vehicle. */
@Entity
public class Vehicle {

  @Id private Long id;
}
