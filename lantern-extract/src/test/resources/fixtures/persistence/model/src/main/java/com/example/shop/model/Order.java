package com.example.shop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = Tables.ORDERS)
public class Order {

  @Id private Long id;
}
