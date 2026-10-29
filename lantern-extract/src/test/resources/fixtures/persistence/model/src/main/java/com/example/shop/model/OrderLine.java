package com.example.shop.model;

import jakarta.persistence.*;

/** No @Table: Spring Boot names the table order_line. */
@Entity
public class OrderLine {

  @Id private Long id;
}
