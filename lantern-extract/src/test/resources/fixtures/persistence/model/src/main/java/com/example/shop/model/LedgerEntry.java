package com.example.shop.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Spring Data JDBC: the name given is used as written. */
@Table("ledger")
public class LedgerEntry {

  @Id private Long id;
}
