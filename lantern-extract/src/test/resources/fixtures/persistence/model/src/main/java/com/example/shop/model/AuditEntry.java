package com.example.shop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A schema, and a camel-case name Spring Boot puts in snake case. */
@Entity
@Table(schema = "audit", name = "AuditEntry")
public class AuditEntry {

  @Id private Long id;
}
