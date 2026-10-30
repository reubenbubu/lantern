package com.example.shop.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Under Hibernate's standard naming, set in config, the name is kept as written. */
@Entity
@Table(name = "CatalogItem", schema = "Shop")
public class CatalogItem {}
