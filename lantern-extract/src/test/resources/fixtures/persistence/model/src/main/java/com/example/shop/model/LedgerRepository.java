package com.example.shop.model;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

public interface LedgerRepository extends ListCrudRepository<LedgerEntry, Long> {

  @Query("UPDATE ledger SET amount = 0 WHERE id = :id")
  void reset(long id);
}
